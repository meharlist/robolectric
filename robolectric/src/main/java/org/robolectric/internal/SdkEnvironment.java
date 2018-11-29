package org.robolectric.internal;

import com.google.common.collect.Lists;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;
import org.robolectric.ApkLoader;
import org.robolectric.android.internal.ParallelUniverse;
import org.robolectric.annotation.Config;
import org.robolectric.internal.bytecode.Sandbox;
import org.robolectric.internal.dependency.DependencyJar;
import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.Fs;
import org.robolectric.res.PackageResourceTable;
import org.robolectric.res.ResourcePath;
import org.robolectric.res.ResourceTableFactory;

@SuppressWarnings("NewApi")
public class SdkEnvironment extends Sandbox {
  private final SdkConfig sdkConfig;
  private final ExecutorService executorService;
  private final ParallelUniverseInterface parallelUniverse;
  private final List<ShadowProvider> shadowProviders;

  private Path compileTimeSystemResourcesFile;
  private PackageResourceTable systemResourceTable;

  SdkEnvironment(SdkConfig sdkConfig, boolean useLegacyResources, ClassLoader robolectricClassLoader) {
    super(robolectricClassLoader);

    this.sdkConfig = sdkConfig;

    executorService = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r,
          "main thread for SdkEnvironment(sdk=" + sdkConfig + "; " +
              "resources=" + (useLegacyResources ? "legacy" : "binary") + ")");
      thread.setContextClassLoader(robolectricClassLoader);
      return thread;
    });

    parallelUniverse = getParallelUniverse();

    this.shadowProviders =
        Lists.newArrayList(ServiceLoader.load(ShadowProvider.class, robolectricClassLoader));
  }

  public synchronized Path getCompileTimeSystemResourcesFile(
      DependencyResolver dependencyResolver) {
    if (compileTimeSystemResourcesFile == null) {
      DependencyJar compileTimeJar = new SdkConfig(27).getAndroidSdkDependency();
      compileTimeSystemResourcesFile =
          Paths.get(dependencyResolver.getLocalArtifactUrl(compileTimeJar).getFile());
    }
    return compileTimeSystemResourcesFile;
  }

  public synchronized PackageResourceTable getSystemResourceTable(DependencyResolver dependencyResolver) {
    if (systemResourceTable == null) {
      ResourcePath resourcePath = createRuntimeSdkResourcePath(dependencyResolver);
      systemResourceTable = new ResourceTableFactory().newFrameworkResourceTable(resourcePath);
    }
    return systemResourceTable;
  }

  @Nonnull
  private ResourcePath createRuntimeSdkResourcePath(DependencyResolver dependencyResolver) {
    try {
      URL sdkUrl = dependencyResolver.getLocalArtifactUrl(sdkConfig.getAndroidSdkDependency());
      FileSystem zipFs = Fs.forJar(sdkUrl);
      Class<?> androidRClass = getRobolectricClassLoader().loadClass("android.R");
      Class<?> androidInternalRClass = getRobolectricClassLoader().loadClass("com.android.internal.R");
      // TODO: verify these can be loaded via raw-res path
      return new ResourcePath(
          androidRClass,
          zipFs.getPath("raw-res/res"),
          zipFs.getPath("raw-res/assets"),
          androidInternalRClass);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public SdkConfig getSdkConfig() {
    return sdkConfig;
  }

  @SuppressWarnings("NewApi")
  private ParallelUniverseInterface getParallelUniverse() {
    try {
      return bootstrappedClass(ParallelUniverse.class)
          .asSubclass(ParallelUniverseInterface.class)
          .getConstructor()
          .newInstance();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  public <V> V executeSynchronously(Runnable runnable) {
    Future<V> future = executorService.submit(() -> {
      runnable.run();
      return null;
    });
    try {
      return future.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  public <V> V executeSynchronously(Callable<V> callable) throws Exception {
    Future<V> future = executorService.submit(callable);
    return future.get();
  }

  public void initialize(ApkLoader apkLoader, MethodConfig methodConfig) {
    parallelUniverse.setSdkConfig(sdkConfig);
    parallelUniverse.setResourcesMode(methodConfig.useLegacyResources());

    executeSynchronously(() ->
        parallelUniverse.setUpApplicationState(
            apkLoader,
            methodConfig.getMethod(),
            methodConfig.getConfig(),
            methodConfig.getAppManifest(),
            this)
    );
  }

  public void tearDown() {
    executeSynchronously(parallelUniverse::tearDownApplication);
  }

  public void reset() {
    executeSynchronously(() -> {
      for (ShadowProvider shadowProvider : shadowProviders) {
        shadowProvider.reset();
      }
    });
  }

  public interface MethodConfig {
    Method getMethod();

    Config getConfig();

    AndroidManifest getAppManifest();

    boolean useLegacyResources();
  }

}

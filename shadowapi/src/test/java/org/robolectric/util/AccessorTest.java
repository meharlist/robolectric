package org.robolectric.util;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.util.ReflectionHelpers.accessorFor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.util.Collections;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.robolectric.util.ReflectionHelpers.WithType;

@RunWith(JUnit4.class)
public class AccessorTest {

  @Test
  public void accessorFor_shouldCallPrivateMethod() throws Exception {
    SomeClass someClass = new SomeClass("c");
    assertThat(
        accessorFor(_SomeClass_.class, someClass)
            .someMethod("a", "b"))
        .isEqualTo("a-b-c (someMethod)");
  }

  @Test
  public void accessorFor_shouldHonorWithTypeAnnotationForParams() throws Exception {
    SomeClass someClass = new SomeClass("c");
    assertThat(
        accessorFor(_SomeClass_.class, someClass)
            .anotherMethod("a", "b"))
        .isEqualTo("a-b-c (anotherMethod)");
  }

  @Ignore @Test
  public void perf() throws Exception {
    SomeClass i = new SomeClass("c");

    System.out.println("reflection = " + Collections.singletonList(byReflection(i)));
    time("reflection", 10_000_000, () -> byReflection(i));

    System.out.println("accessor = " + Collections.singletonList(byAccessor(i)));
    time("accessor", 10_000_000, () -> byAccessor(i));

    time("reflection", 10_000_000, () -> byReflection(i));
    time("accessor", 10_000_000, () -> byAccessor(i));
  }

  //////////////////////

  interface _SomeClass_ {

    @SuppressWarnings("unused")
    Lookup LOOKUP = MethodHandles.lookup();

    String someMethod(String a, String b);

    String anotherMethod(@WithType("java.lang.String") Object a, String b);
  }

  public static class SomeClass {

    private String c;

    SomeClass(String c) {
      this.c = c;
    }

    @SuppressWarnings("unused")
    private String someMethod(String a, String b) {
      return a + "-" + b + "-" + c + " (someMethod)";
    }

    @SuppressWarnings("unused")
    private String anotherMethod(String a, String b) {
      return a + "-" + b + "-" + c + " (anotherMethod)";
    }
  }

  private void time(String name, int times, Runnable runnable) {
    long startTime = System.currentTimeMillis();
    for (int i = 0; i < times; i++) {
      runnable.run();
    }
    long elasedMs = System.currentTimeMillis() - startTime;
    System.out.println(name + " took " + elasedMs);
  }

  private String byReflection(SomeClass i) {
    return ReflectionHelpers.callInstanceMethod(i, "someMethod",
        ClassParameter.from(String.class, "a"),
        ClassParameter.from(String.class, "b"));
  }

  private String byAccessor(SomeClass i) {
    _SomeClass_ accessor = ReflectionHelpers.accessorFor(_SomeClass_.class, i);
    return accessor.someMethod("a", "b");
  }

}

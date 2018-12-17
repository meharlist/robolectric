package org.robolectric.util;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Collection of helper methods for calling methods and accessing fields reflectively.
 */
@SuppressWarnings(value = {"unchecked", "TypeParameterUnusedInFormals", "NewApi"})
public class ReflectionHelpers {

  private static final Map<String, Object> PRIMITIVE_RETURN_VALUES;

  static {
    HashMap<String, Object> map = new HashMap<>();
    map.put("boolean", Boolean.FALSE);
    map.put("int", 0);
    map.put("long", (long) 0);
    map.put("float", (float) 0);
    map.put("double", (double) 0);
    map.put("short", (short) 0);
    map.put("byte", (byte) 0);
    PRIMITIVE_RETURN_VALUES = Collections.unmodifiableMap(map);
  }

  public static <T> T createNullProxy(Class<T> clazz) {
    return (T) Proxy.newProxyInstance(clazz.getClassLoader(),
        new Class[]{clazz},
        (proxy, method, args) -> PRIMITIVE_RETURN_VALUES.get(method.getReturnType().getName()));
  }

  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface ForType {
    Class<?> value();
  }

  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface WithType {
    String value();
  }

  /**
   * Values are held via {@link WeakReference}, so if any class becomes otherwise unreachable
   * it can be garbage collected.
   */
  private static class WeakerHashMap<K, V> implements Map<K, V> {

    public final Map<K, WeakReference<V>> map = new WeakHashMap<>();

    @Override
    public int size() {
      return map.size();
    }

    @Override
    public boolean isEmpty() {
      return map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
      return map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public V get(Object key) {
      WeakReference<V> ref = map.get(key);
      if (ref != null) {
        V v = ref.get();
        if (v == null) {
          map.remove(key);
        }
        return v;
      }
      return null;
    }

    @Override
    public V put(K key, V value) {
      WeakReference<V> oldV = map.put(key, new WeakReference<>(value));
      return oldV == null ? null : oldV.get();
    }

    @Override
    public V remove(Object key) {
      WeakReference<V> oldV = map.remove(key);
      return oldV == null ? null : oldV.get();
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
      map.clear();
    }

    @Override
    public Set<K> keySet() {
      return map.keySet();
    }

    @Override
    public Collection<V> values() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
      throw new UnsupportedOperationException();
    }
  }

  private static final Map<Class<?>, Constructor<?>> PROXY_CACHE =
      Collections.synchronizedMap(new WeakerHashMap<>());
  private static final Map<Method, Method> METHOD_CACHE =
      Collections.synchronizedMap(new WeakerHashMap<>());

  /**
   * Returns an object which provides accessors for invoking otherwise inaccessible methods.
   * @param iClass an interface with methods matching private methods on the target
   * @param target the target object
   */
  public static <T> T accessorFor(Class<T> iClass, Object target) {
    ForType forType = iClass.getAnnotation(ForType.class);
    Class<?> targetClass = forType == null ? target.getClass() : forType.value();

    InvocationHandler h = (proxy, method, args) -> {
      if (method.isDefault()) {
        method.setAccessible(true);
        Class<?> declaringClass = method.getDeclaringClass();
        return MethodHandles.lookup()
            .in(declaringClass)
            .unreflectSpecial(method, declaringClass)
            .bindTo(proxy)
            .invokeWithArguments(args);
      }

      Method targetMethod = METHOD_CACHE.computeIfAbsent(method,
          m -> {
            String name = method.getName();
            Class<?>[] parameterTypes = method.getParameterTypes();
            try {
              Method resolvedMethod = targetClass.getDeclaredMethod(name, parameterTypes);
              resolvedMethod.setAccessible(true);
              return resolvedMethod;
            } catch (NoSuchMethodException e) {
              // try again...
            }

            Annotation[][] paramAnnotations = method.getParameterAnnotations();
            resolveParamTypes(iClass, parameterTypes, paramAnnotations);

            try {
              Method resolvedMethod = targetClass.getDeclaredMethod(name, parameterTypes);
              resolvedMethod.setAccessible(true);
              return resolvedMethod;
            } catch (NoSuchMethodException e) {
              throw new IllegalArgumentException(e);
            }
          });
      return targetMethod.invoke(target, args);
    };

    Constructor<?> proxyCtor = PROXY_CACHE.get(iClass);
    try {
      if (proxyCtor != null) {
        return (T) proxyCtor.newInstance(h);
      }

      T proxy = (T) Proxy.newProxyInstance(iClass.getClassLoader(), new Class[]{iClass}, h);
      Constructor<?> ctor = proxy.getClass().getConstructor(InvocationHandler.class);
      ctor.setAccessible(true);
      PROXY_CACHE.put(iClass, ctor);
      return proxy;
    } catch (NoSuchMethodException | InstantiationException
        | IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> void resolveParamTypes(Class<T> iClass, Class<?>[] parameterTypes,
      Annotation[][] paramAnnotations) {
    for (int i = 0; i < parameterTypes.length; i++) {
      for (Annotation annotation : paramAnnotations[i]) {
        if (annotation instanceof WithType) {
          String withTypeName = ((WithType) annotation).value();
          try {
            parameterTypes[i] = Class.forName(withTypeName, true, iClass.getClassLoader());
          } catch (ClassNotFoundException e1) {
            throw new IllegalArgumentException(e1);
          }
        }
      }
    }
  }

  /**
   * Create a proxy for the given class which returns other deep proxies from all it's methods.
   *
   * <p>The returned object will be an instance of the given class, but all methods will return
   * either the "default" value for primitives, or another deep proxy for non-primitive types.
   *
   * <p>This should be used rarely, for cases where we need to create deep proxies in order not
   * to crash. The inner proxies are impossible to configure, so there is no way to create
   * meaningful behavior from a deep proxy. It serves mainly to prevent Null Pointer Exceptions.
   * @param clazz the class to provide a proxy instance of.
   * @return a new "Deep Proxy" instance of the given class.
   */
  public static <T> T createDeepProxy(Class<T> clazz) {
    return (T) Proxy.newProxyInstance(clazz.getClassLoader(),
        new Class[] {clazz}, new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (PRIMITIVE_RETURN_VALUES.containsKey(method.getReturnType().getName())) {
              return PRIMITIVE_RETURN_VALUES.get(method.getReturnType().getName());
            } else if (method.getReturnType() == Void.TYPE) {
              return null;
            } else {
              return createDeepProxy(method.getReturnType());
            }
          }
        });
  }

  public static <T> T createDelegatingProxy(Class<T> clazz, final Object delegate) {
    final Class delegateClass = delegate.getClass();
    return (T) Proxy.newProxyInstance(clazz.getClassLoader(),
        new Class[]{clazz}, new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
              Method delegateMethod = delegateClass.getMethod(method.getName(), method.getParameterTypes());
              delegateMethod.setAccessible(true);
              return delegateMethod.invoke(delegate, args);
            } catch (NoSuchMethodException e) {
              return PRIMITIVE_RETURN_VALUES.get(method.getReturnType().getName());
            }
          }
        });
  }

  public static <A extends Annotation> A defaultsFor(Class<A> annotation) {
    return annotation.cast(
        Proxy.newProxyInstance(annotation.getClassLoader(), new Class[] { annotation },
            new InvocationHandler() {
              @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                return method.getDefaultValue();
              }
            }));
  }

  /**
   * Reflectively get the value of a field.
   *
   * @param object Target object.
   * @param fieldName The field name.
   * @param <R> The return type.
   * @return Value of the field on the object.
   */
  @SuppressWarnings("unchecked")
  public static <R> R getField(final Object object, final String fieldName) {
    try {
      return traverseClassHierarchy(object.getClass(), NoSuchFieldException.class, new InsideTraversal<R>() {
        @Override
        public R run(Class<?> traversalClass) throws Exception {
          Field field = traversalClass.getDeclaredField(fieldName);
          field.setAccessible(true);
          return (R) field.get(object);
        }
      });
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively set the value of a field.
   *
   * @param object Target object.
   * @param fieldName The field name.
   * @param fieldNewValue New value.
   */
  public static void setField(final Object object, final String fieldName, final Object fieldNewValue) {
    try {
      traverseClassHierarchy(object.getClass(), NoSuchFieldException.class, new InsideTraversal<Void>() {
        @Override
        public Void run(Class<?> traversalClass) throws Exception {
          Field field = traversalClass.getDeclaredField(fieldName);
          field.setAccessible(true);
          field.set(object, fieldNewValue);
          return null;
        }
      });
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively set the value of a field.
   *
   * @param type Target type.
   * @param object Target object.
   * @param fieldName The field name.
   * @param fieldNewValue New value.
   */
  public static void setField(Class<?> type, final Object object, final String fieldName, final Object fieldNewValue) {
    try {
      Field field = type.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(object, fieldNewValue);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively get the value of a static field.
   *
   * @param field Field object.
   * @param <R> The return type.
   * @return Value of the field.
   */
  @SuppressWarnings("unchecked")
  public static <R> R getStaticField(Field field) {
    try {
      makeFieldVeryAccessible(field);
      return (R) field.get(null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively get the value of a static field.
   *
   * @param clazz Target class.
   * @param fieldName The field name.
   * @param <R> The return type.
   * @return Value of the field.
   */
  public static <R> R getStaticField(Class<?> clazz, String fieldName) {
    try {
      return getStaticField(clazz.getDeclaredField(fieldName));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively set the value of a static field.
   *
   * @param field Field object.
   * @param fieldNewValue The new value.
   */
  public static void setStaticField(Field field, Object fieldNewValue) {
    try {
      makeFieldVeryAccessible(field);
      field.set(null, fieldNewValue);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively set the value of a static field.
   *
   * @param clazz Target class.
   * @param fieldName The field name.
   * @param fieldNewValue The new value.
   */
  public static void setStaticField(Class<?> clazz, String fieldName, Object fieldNewValue) {
    try {
      setStaticField(clazz.getDeclaredField(fieldName), fieldNewValue);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively call an instance method on an object.
   *
   * @param instance Target object.
   * @param methodName The method name to call.
   * @param classParameters Array of parameter types and values.
   * @param <R> The return type.
   * @return The return value of the method.
   */
  public static <R> R callInstanceMethod(final Object instance, final String methodName, ClassParameter<?>... classParameters) {
    try {
      final Class<?>[] classes = ClassParameter.getClasses(classParameters);
      final Object[] values = ClassParameter.getValues(classParameters);

      return traverseClassHierarchy(instance.getClass(), NoSuchMethodException.class, new InsideTraversal<R>() {
        @Override
        @SuppressWarnings("unchecked")
        public R run(Class<?> traversalClass) throws Exception {
          Method declaredMethod = traversalClass.getDeclaredMethod(methodName, classes);
          declaredMethod.setAccessible(true);
          return (R) declaredMethod.invoke(instance, values);
        }
      });
    } catch (InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException) {
        throw (RuntimeException) e.getTargetException();
      }
      if (e.getTargetException() instanceof Error) {
        throw (Error) e.getTargetException();
      }
      throw new RuntimeException(e.getTargetException());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively call an instance method on an object on a specific class.
   *
   * @param cl The class.
   * @param instance Target object.
   * @param methodName The method name to call.
   * @param classParameters Array of parameter types and values.
   * @param <R> The return type.
   * @return The return value of the method.
   */
  public static <R> R callInstanceMethod(Class<?> cl, final Object instance, final String methodName, ClassParameter<?>... classParameters) {
    try {
      final Class<?>[] classes = ClassParameter.getClasses(classParameters);
      final Object[] values = ClassParameter.getValues(classParameters);

      Method method = cl.getDeclaredMethod(methodName, classes);
      method.setAccessible(true);
      if (Modifier.isStatic(method.getModifiers())) {
        throw new IllegalArgumentException(method + " is static");
      }
      return (R) method.invoke(instance, values);
    } catch (InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException) {
        throw (RuntimeException) e.getTargetException();
      }
      if (e.getTargetException() instanceof Error) {
        throw (Error) e.getTargetException();
      }
      throw new RuntimeException(e.getTargetException());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively call a static method on a class.
   *
   * @param clazz Target class.
   * @param methodName The method name to call.
   * @param classParameters Array of parameter types and values.
   * @param <R> The return type.
   * @return The return value of the method.
   */
  @SuppressWarnings("unchecked")
  public static <R> R callStaticMethod(Class<?> clazz, String methodName, ClassParameter<?>... classParameters) {
    try {
      Class<?>[] classes = ClassParameter.getClasses(classParameters);
      Object[] values = ClassParameter.getValues(classParameters);

      Method method = clazz.getDeclaredMethod(methodName, classes);
      method.setAccessible(true);
      if (!Modifier.isStatic(method.getModifiers())) {
        throw new IllegalArgumentException(method + " is not static");
      }
      return (R) method.invoke(null, values);
    } catch (InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException) {
        throw (RuntimeException) e.getTargetException();
      }
      if (e.getTargetException() instanceof Error) {
        throw (Error) e.getTargetException();
      }
      throw new RuntimeException(e.getTargetException());
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("no such method " + clazz + "." + methodName, e);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Load a class.
   *
   * @param classLoader The class loader.
   * @param fullyQualifiedClassName The fully qualified class name.
   * @return The class object.
   */
  public static Class<?> loadClass(ClassLoader classLoader, String fullyQualifiedClassName) {
    try {
      return classLoader.loadClass(fullyQualifiedClassName);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Create a new instance of a class
   *
   * @param cl The class object.
   * @param <T> The class type.
   * @return New class instance.
   */
  public static <T> T newInstance(Class<T> cl) {
    try {
      return cl.getDeclaredConstructor().newInstance();
    } catch (InstantiationException | IllegalAccessException | NoSuchMethodException
        | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Reflectively call the constructor of an object.
   *
   * @param clazz Target class.
   * @param classParameters Array of parameter types and values.
   * @param <R> The return type.
   * @return The return value of the method.
   */
  public static <R> R callConstructor(Class<? extends R> clazz, ClassParameter<?>... classParameters) {
    try {
      final Class<?>[] classes = ClassParameter.getClasses(classParameters);
      final Object[] values = ClassParameter.getValues(classParameters);

      Constructor<? extends R> constructor = clazz.getDeclaredConstructor(classes);
      constructor.setAccessible(true);
      return constructor.newInstance(values);
    } catch (InstantiationException e) {
      throw new RuntimeException("error instantiating " + clazz.getName(), e);
    } catch (InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException) {
        throw (RuntimeException) e.getTargetException();
      }
      if (e.getTargetException() instanceof Error) {
        throw (Error) e.getTargetException();
      }
      throw new RuntimeException(e.getTargetException());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static <R, E extends Exception> R traverseClassHierarchy(Class<?> targetClass, Class<? extends E> exceptionClass, InsideTraversal<R> insideTraversal) throws Exception {
    Class<?> hierarchyTraversalClass = targetClass;
    while (true) {
      try {
        return insideTraversal.run(hierarchyTraversalClass);
      } catch (Exception e) {
        if (!exceptionClass.isInstance(e)) {
          throw e;
        }
        hierarchyTraversalClass = hierarchyTraversalClass.getSuperclass();
        if (hierarchyTraversalClass == null) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private static void makeFieldVeryAccessible(Field field) {
    field.setAccessible(true);

    try {
      Field modifiersField = Field.class.getDeclaredField("modifiers");
      modifiersField.setAccessible(true);
      try {
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    } catch (NoSuchFieldException e) {
      // ignore missing fields
    }
  }

  public static Object defaultValueForType(String returnType) {
    return PRIMITIVE_RETURN_VALUES.get(returnType);
  }

  private interface InsideTraversal<R> {
    R run(Class<?> traversalClass) throws Exception;
  }

  /**
   * Typed parameter used with reflective method calls.
   *
   * @param <V> The value of the method parameter.
   */
  public static class ClassParameter<V> {
    public final Class<? extends V> clazz;
    public final V val;

    public ClassParameter(Class<? extends V> clazz, V val) {
      this.clazz = clazz;
      this.val = val;
    }

    public static <V> ClassParameter<V> from(Class<? extends V> clazz, V val) {
      return new ClassParameter<>(clazz, val);
    }

    public static ClassParameter<?>[] fromComponentLists(Class<?>[] classes, Object[] values) {
      ClassParameter<?>[] classParameters = new ClassParameter[classes.length];
      for (int i = 0; i < classes.length; i++) {
        classParameters[i] = ClassParameter.from(classes[i], values[i]);
      }
      return classParameters;
    }

    public static Class<?>[] getClasses(ClassParameter<?>... classParameters) {
      Class<?>[] classes = new Class[classParameters.length];
      for (int i = 0; i < classParameters.length; i++) {
        Class<?> paramClass = classParameters[i].clazz;
        classes[i] = paramClass;
      }
      return classes;
    }

    public static Object[] getValues(ClassParameter<?>... classParameters) {
      Object[] values = new Object[classParameters.length];
      for (int i = 0; i < classParameters.length; i++) {
        Object paramValue = classParameters[i].val;
        values[i] = paramValue;
      }
      return values;
    }
  }

  /**
   * String parameter used with reflective method calls.
   *
   * @param <V> The value of the method parameter.
   */
  public static class StringParameter<V> {
    public final String className;
    public final V val;

    public StringParameter(String className, V val) {
      this.className = className;
      this.val = val;
    }

    public static <V> StringParameter<V> from(String className, V val) {
      return new StringParameter<>(className, val);
    }
  }
}

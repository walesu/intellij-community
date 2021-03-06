// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.extensions.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.CollectionFactory;
import org.jdom.Element;
import org.jetbrains.annotations.*;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@ApiStatus.Internal
public final class ExtensionsAreaImpl implements ExtensionsArea {
  private static final Logger LOG = Logger.getInstance(ExtensionsAreaImpl.class);

  private static final boolean DEBUG_REGISTRATION = false;

  private final ComponentManager componentManager;
  private final Map<String, ExtensionPointImpl<?>> extensionPoints = new ConcurrentHashMap<>();
  private final Map<String,Throwable> epTraces = DEBUG_REGISTRATION ? CollectionFactory.createSmallMemoryFootprintMap() : null;

  public ExtensionsAreaImpl(@NotNull ComponentManager componentManager) {
    this.componentManager = componentManager;
  }

  @TestOnly
  public final void notifyAreaReplaced(@Nullable ExtensionsAreaImpl newArea) {
    Set<String> processedEPs = new HashSet<>(extensionPoints.size());
    for (ExtensionPointImpl<?> point : extensionPoints.values()) {
      point.notifyAreaReplaced(this);
      processedEPs.add(point.getName());
    }

    if (newArea == null) {
      return;
    }

    for (ExtensionPointImpl<?> point : newArea.extensionPoints.values()) {
      if (!processedEPs.contains(point.getName())) {
        point.notifyAreaReplaced(this);
      }
    }
  }

  @TestOnly
  public void registerExtensionPoints(@NotNull PluginDescriptor pluginDescriptor, @NotNull List<Element> extensionPointElements) {
    for (Element element : extensionPointElements) {
      String pointName = element.getAttributeValue("qualifiedName");
      if (pointName == null) {
        String name = element.getAttributeValue("name");
        if (name == null) {
          throw componentManager.createError("'name' attribute not specified for extension point in '" + pluginDescriptor + "' plugin",
                                             pluginDescriptor.getPluginId());
        }

        assert pluginDescriptor.getPluginId() != null;
        pointName = pluginDescriptor.getPluginId().getIdString() + '.' + name;
      }

      String beanClassName = element.getAttributeValue("beanClass");
      String interfaceClassName = element.getAttributeValue("interface");
      if (beanClassName == null && interfaceClassName == null) {
        throw componentManager.createError("Neither 'beanClass' nor 'interface' attribute is specified for extension point '" + pointName + "' in '" +
                                           pluginDescriptor + "' plugin", pluginDescriptor.getPluginId());
      }

      if (beanClassName != null && interfaceClassName != null) {
        throw componentManager.createError("Both 'beanClass' and 'interface' attributes are specified for extension point '" + pointName + "' in '" +
                                           pluginDescriptor + "' plugin", pluginDescriptor.getPluginId());
      }

      boolean dynamic = Boolean.parseBoolean(element.getAttributeValue("dynamic"));
      String className = interfaceClassName == null ? beanClassName : interfaceClassName;
      doRegisterExtensionPoint(pointName, className, pluginDescriptor, interfaceClassName != null, dynamic);
    }
  }

  public boolean unregisterExtensions(@NotNull String extensionPointName,
                                      @NotNull PluginDescriptor pluginDescriptor,
                                      @NotNull List<ExtensionDescriptor> elements,
                                      @NotNull List<Runnable> priorityListenerCallbacks,
                                      @NotNull List<Runnable> listenerCallbacks) {
    ExtensionPointImpl<?> point = extensionPoints.get(extensionPointName);
    if (point == null) {
      return false;
    }

    point.unregisterExtensions(componentManager, pluginDescriptor, elements, priorityListenerCallbacks, listenerCallbacks);
    return true;
  }

  // extensionPoints here are raw and not initialized (not the same instance, only name can be used)
  public void resetExtensionPoints(@NotNull List<? extends ExtensionPointImpl<?>> rawExtensionPoints) {
    for (ExtensionPointImpl<?> point : rawExtensionPoints) {
      ExtensionPointImpl<?> extensionPoint = extensionPoints.get(point.getName());
      if (extensionPoint != null) {
        extensionPoint.reset();
      }
    }
  }

  public void clearUserCache() {
    extensionPoints.values().forEach(ExtensionPointImpl::clearUserCache);
  }

  // note about extension point here the same as for resetExtensionPoints
  /**
   * You must call {@link #resetExtensionPoints} before otherwise event ExtensionEvent.REMOVED will be not fired.
   */
  public void unregisterExtensionPoints(@NotNull List<? extends ExtensionPointImpl<?>> rawExtensionPoints) {
    for (ExtensionPointImpl<?> point : rawExtensionPoints) {
      extensionPoints.remove(point.getName());
    }
  }

  @Override
  @TestOnly
  public void registerExtensionPoint(@NotNull @NonNls String extensionPointName,
                                     @NotNull String extensionPointBeanClass,
                                     @NotNull ExtensionPoint.Kind kind) {
    doRegisterExtensionPoint(extensionPointName, extensionPointBeanClass, kind);
  }

  @Override
  @TestOnly
  public void registerDynamicExtensionPoint(@NonNls @NotNull String extensionPointName,
                                            @NotNull String extensionPointBeanClass,
                                            ExtensionPoint.@NotNull Kind kind) {
    doRegisterExtensionPoint(extensionPointName, extensionPointBeanClass, kind, true);
  }

  @TestOnly
  public void registerExtensionPoint(@NotNull BaseExtensionPointName<?> extensionPoint,
                                     @NotNull String extensionPointBeanClass,
                                     @NotNull ExtensionPoint.Kind kind,
                                     @NotNull Disposable parentDisposable) {
    String extensionPointName = extensionPoint.getName();
    doRegisterExtensionPoint(extensionPointName, extensionPointBeanClass, kind);
    Disposer.register(parentDisposable, () -> unregisterExtensionPoint(extensionPointName));
  }

  @TestOnly
  void doRegisterExtensionPoint(@NotNull String extensionPointName, @NotNull String extensionPointBeanClass, @NotNull ExtensionPoint.Kind kind) {
    doRegisterExtensionPoint(extensionPointName, extensionPointBeanClass, kind, false);
  }

  @TestOnly
  void doRegisterExtensionPoint(@NotNull String extensionPointName, @NotNull String extensionPointBeanClass, @NotNull ExtensionPoint.Kind kind, boolean dynamic) {
    PluginDescriptor pluginDescriptor = new DefaultPluginDescriptor(PluginId.getId("FakeIdForTests"));
    doRegisterExtensionPoint(extensionPointName, extensionPointBeanClass, pluginDescriptor, kind == ExtensionPoint.Kind.INTERFACE, dynamic);
  }

  @TestOnly
  public <T> ExtensionPointImpl<T> registerPoint(@NotNull String name,
                                                 @NotNull Class<T> extensionClass,
                                                 @NotNull PluginDescriptor pluginDescriptor) {
    return doRegisterExtensionPoint(name, extensionClass.getName(), pluginDescriptor, extensionClass.isInterface() || (extensionClass.getModifiers() & Modifier.ABSTRACT) != 0,
                                    false);
  }

  private @NotNull <T> ExtensionPointImpl<T> doRegisterExtensionPoint(@NotNull String name, @NotNull String extensionClass,
                                                                      @NotNull PluginDescriptor pluginDescriptor, boolean isInterface, boolean dynamic) {
    ExtensionPointImpl<T> point;
    if (isInterface) {
      point = new InterfaceExtensionPoint<>(name, extensionClass, pluginDescriptor, null, dynamic);
    }
    else {
      point = new BeanExtensionPoint<>(name, extensionClass, pluginDescriptor, dynamic);
    }
    point.setComponentManager(componentManager);
    registerExtensionPoint(point);
    return point;
  }

  /**
   * To register extensions for {@link com.intellij.openapi.util.KeyedExtensionCollector} for test purposes, where extension instance can be KeyedLazyInstance and not a real bean class,
   * because often it is not possible to use one (for example, {@link com.intellij.lang.LanguageExtensionPoint}).
   */
  @TestOnly
  public <T> ExtensionPointImpl<T> registerFakeBeanPoint(@NotNull String name, @NotNull PluginDescriptor pluginDescriptor) {
    // any object name can be used, because EP must not create any instance
    return doRegisterExtensionPoint(name, Object.class.getName(), pluginDescriptor, false, false);
  }

  private void checkThatPointNotDuplicated(@NotNull String pointName, @NotNull PluginDescriptor pluginDescriptor) {
    if (!hasExtensionPoint(pointName)) {
      return;
    }

    PluginId id1 = getExtensionPoint(pointName).getPluginDescriptor().getPluginId();
    PluginId id2 = pluginDescriptor.getPluginId();
    @NonNls String message = "Duplicate registration for EP '" + pointName + "': first in " + id1 + ", second in " + id2;
    if (DEBUG_REGISTRATION) {
      LOG.error(message, epTraces.get(pointName));
    }
    throw componentManager.createError(message, pluginDescriptor.getPluginId());
  }

  private void registerExtensionPoint(@NotNull ExtensionPointImpl<?> point) {
    String name = point.getName();
    checkThatPointNotDuplicated(name, point.getPluginDescriptor());
    extensionPoints.put(name, point);
    if (DEBUG_REGISTRATION) {
      epTraces.put(name, new Throwable("Original registration for " + name));
    }
  }

  @ApiStatus.Internal
  public void registerExtensionPoints(@NotNull List<ExtensionPointImpl<?>> points, boolean clonePoint) {
    ComponentManager componentManager = this.componentManager;
    Map<String, ExtensionPointImpl<?>> map = extensionPoints;
    for (ExtensionPointImpl<?> point : points) {
      if (clonePoint) {
        point = point.cloneFor(componentManager);
      }
      else {
        point.setComponentManager(componentManager);
      }

      ExtensionPointImpl<?> old = map.put(point.getName(), point);
      if (old != null) {
        map.put(point.getName(), old);
        PluginDescriptor oldPluginDescriptor = old.getPluginDescriptor();
        PluginDescriptor pluginDescriptor = point.getPluginDescriptor();
        throw componentManager.createError("Duplicate registration for EP '" + point.getName() + "': " +
                                           "first in " + oldPluginDescriptor + " (" + oldPluginDescriptor.getPluginPath() + ")" +
                                           ", second in " + pluginDescriptor+ " (" + pluginDescriptor.getPluginPath() + ")",
                                           pluginDescriptor.getPluginId());
      }
    }
  }

  @Override
  public @NotNull <T> ExtensionPointImpl<T> getExtensionPoint(@NotNull String extensionPointName) {
    @SuppressWarnings("unchecked")
    ExtensionPointImpl<T> extensionPoint = (ExtensionPointImpl<T>)extensionPoints.get(extensionPointName);
    if (extensionPoint == null) {
      throw new IllegalArgumentException("Missing extension point: " + extensionPointName + " in container " + componentManager);
    }
    return extensionPoint;
  }

  public void registerExtensions(@NotNull Map<String, List<ExtensionDescriptor>> extensions,
                                 @NotNull PluginDescriptor pluginDescriptor,
                                 @Nullable List<Runnable> listenerCallbacks) {
    extensions.forEach((name, list) -> {
      ExtensionPointImpl<?> point = extensionPoints.get(name);
      if (point != null) {
        point.registerExtensions(list, pluginDescriptor, componentManager, listenerCallbacks);
      }
    });
  }

  public boolean registerExtensions(@NotNull String pointName,
                                    @NotNull List<ExtensionDescriptor> extensions,
                                    @NotNull PluginDescriptor pluginDescriptor,
                                    @Nullable List<Runnable> listenerCallbacks)  {
    ExtensionPointImpl<?> point = extensionPoints.get(pointName);
    if (point == null) {
      return false;
    }

    point.registerExtensions(extensions, pluginDescriptor, componentManager, listenerCallbacks);
    return true;
  }

  @Override
  public @Nullable <T> ExtensionPointImpl<T> getExtensionPointIfRegistered(@NotNull String extensionPointName) {
    //noinspection unchecked
    return (ExtensionPointImpl<T>)extensionPoints.get(extensionPointName);
  }

  @Override
  public @NotNull <T> ExtensionPoint<T> getExtensionPoint(@NotNull ExtensionPointName<T> extensionPointName) {
    return getExtensionPoint(extensionPointName.getName());
  }

  @TestOnly
  public void processExtensionPoints(@NotNull Consumer<? super ExtensionPointImpl<?>> consumer) {
    extensionPoints.values().forEach(consumer);
  }

  @Override
  public @NotNull List<ExtensionPoint<?>> getExtensionPoints() {
    return Collections.unmodifiableList(new ArrayList<>(extensionPoints.values()));
  }

  @ApiStatus.Internal
  public @Nullable <T> T findExtensionByClass(@NotNull Class<T> aClass) {
    // TeamCity plugin wants DefaultDebugExecutor in constructor
    if (aClass.getName().equals("com.intellij.execution.executors.DefaultDebugExecutor")) {
      //noinspection unchecked
      return ((ExtensionPointImpl<T>)extensionPoints.get("com.intellij.executor")).findExtension(aClass, false, ThreeState.YES);
    }

    for (ExtensionPointImpl<?> point : extensionPoints.values()) {
      if (!(point instanceof InterfaceExtensionPoint)) {
        continue;
      }

      try {
        Class<?> extensionClass = point.getExtensionClass();
        if (!extensionClass.isAssignableFrom(aClass)) {
          continue;
        }

        //noinspection unchecked
        T extension = ((ExtensionPointImpl<T>)point).findExtension(aClass, false, ThreeState.YES);
        if (extension != null) {
          return extension;
        }
      }
      catch (Throwable e) {
        LOG.warn("error during findExtensionPointByClass", e);
      }
    }
    return null;
  }

  @Override
  public void unregisterExtensionPoint(@NotNull String extensionPointName) {
    ExtensionPointImpl<?> extensionPoint = extensionPoints.get(extensionPointName);
    if (extensionPoint != null) {
      extensionPoint.reset();
      extensionPoints.remove(extensionPointName);
    }
  }

  @Override
  public boolean hasExtensionPoint(@NotNull String extensionPointName) {
    return extensionPoints.containsKey(extensionPointName);
  }

  @Override
  public boolean hasExtensionPoint(@NotNull ExtensionPointName<?> extensionPointName) {
    return hasExtensionPoint(extensionPointName.getName());
  }

  @Override
  public String toString() {
    return componentManager.toString();
  }
}
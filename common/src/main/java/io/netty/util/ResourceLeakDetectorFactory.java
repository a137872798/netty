/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.reflect.Constructor;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * This static factory should be used to load {@link ResourceLeakDetector}s as needed
 *
 * 内存泄漏工厂对象
 */
public abstract class ResourceLeakDetectorFactory {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ResourceLeakDetectorFactory.class);

    /**
     * 使用默认的 内存泄漏检测工厂
     */
    private static volatile ResourceLeakDetectorFactory factoryInstance = new DefaultResourceLeakDetectorFactory();

    /**
     * Get the singleton instance of this factory class.
     *
     * @return the current {@link ResourceLeakDetectorFactory}
     */
    public static ResourceLeakDetectorFactory instance() {
        return factoryInstance;
    }

    /**
     * Set the factory's singleton instance. This has to be called before the static initializer of the
     * {@link ResourceLeakDetector} is called by all the callers of this factory. That is, before initializing a
     * Netty Bootstrap.
     *
     * 指定特定的  内存泄漏工厂
     * @param factory the instance that will become the current {@link ResourceLeakDetectorFactory}'s singleton
     */
    public static void setResourceLeakDetectorFactory(ResourceLeakDetectorFactory factory) {
        factoryInstance = ObjectUtil.checkNotNull(factory, "factory");
    }

    /**
     * Returns a new instance of a {@link ResourceLeakDetector} with the given resource class.
     *
     * 根据指定类型 来创建 资源泄露对象(ResourceLeakDetector)
     * @param resource the resource class used to initialize the {@link ResourceLeakDetector}
     * @param <T> the type of the resource class
     * @return a new instance of {@link ResourceLeakDetector}
     */
    public final <T> ResourceLeakDetector<T> newResourceLeakDetector(Class<T> resource) {
        return newResourceLeakDetector(resource, ResourceLeakDetector.SAMPLING_INTERVAL);
    }

    /**
     * @deprecated Use {@link #newResourceLeakDetector(Class, int)} instead.
     * <p>
     * Returns a new instance of a {@link ResourceLeakDetector} with the given resource class.
     *
     * @param resource the resource class used to initialize the {@link ResourceLeakDetector}
     * @param samplingInterval the interval on which sampling takes place
     * @param maxActive This is deprecated and will be ignored.
     * @param <T> the type of the resource class
     * @return a new instance of {@link ResourceLeakDetector}
     */
    @Deprecated
    public abstract <T> ResourceLeakDetector<T> newResourceLeakDetector(
            Class<T> resource, int samplingInterval, long maxActive);

    /**
     * Returns a new instance of a {@link ResourceLeakDetector} with the given resource class.
     *
     * @param resource the resource class used to initialize the {@link ResourceLeakDetector}
     * @param samplingInterval the interval on which sampling takes place
     * @param <T> the type of the resource class
     * @return a new instance of {@link ResourceLeakDetector}
     */
    @SuppressWarnings("deprecation")
    public <T> ResourceLeakDetector<T> newResourceLeakDetector(Class<T> resource, int samplingInterval) {
        return newResourceLeakDetector(resource, ResourceLeakDetector.SAMPLING_INTERVAL, Long.MAX_VALUE);
    }

    /**
     * Default implementation that loads custom leak detector via system property
     * 默认的 资源泄漏工厂
     */
    private static final class DefaultResourceLeakDetectorFactory extends ResourceLeakDetectorFactory {
        /**
         * 过时的消费者 类 构造器
         */
        private final Constructor<?> obsoleteCustomClassConstructor;
        /**
         * 消费者类 构造器
         */
        private final Constructor<?> customClassConstructor;

        DefaultResourceLeakDetectorFactory() {
            String customLeakDetector;
            try {
                //获取消费者资源泄露信息
                customLeakDetector = AccessController.doPrivileged(new PrivilegedAction<String>() {
                    @Override
                    public String run() {
                        return SystemPropertyUtil.get("io.netty.customResourceLeakDetector");
                    }
                });
            } catch (Throwable cause) {
                logger.error("Could not access System property: io.netty.customResourceLeakDetector", cause);
                customLeakDetector = null;
            }
            if (customLeakDetector == null) {
                obsoleteCustomClassConstructor = customClassConstructor = null;
            } else {
                //将构造器 名转化为 构造器对象
                obsoleteCustomClassConstructor = obsoleteCustomClassConstructor(customLeakDetector);
                customClassConstructor = customClassConstructor(customLeakDetector);
            }
        }

        /**
         * 将传入的 字符串变成构造器对象
         * @param customLeakDetector
         * @return
         */
        private static Constructor<?> obsoleteCustomClassConstructor(String customLeakDetector) {
            try {
                //反射创建对象
                final Class<?> detectorClass = Class.forName(customLeakDetector, true,
                        PlatformDependent.getSystemClassLoader());

                //如果是 实现了 资源泄露探测器类型的 就 返回 否则 打印异常日志
                if (ResourceLeakDetector.class.isAssignableFrom(detectorClass)) {
                    return detectorClass.getConstructor(Class.class, int.class, long.class);
                } else {
                    logger.error("Class {} does not inherit from ResourceLeakDetector.", customLeakDetector);
                }
            } catch (Throwable t) {
                logger.error("Could not load custom resource leak detector class provided: {}",
                        customLeakDetector, t);
            }
            return null;
        }

        private static Constructor<?> customClassConstructor(String customLeakDetector) {
            try {
                final Class<?> detectorClass = Class.forName(customLeakDetector, true,
                        PlatformDependent.getSystemClassLoader());

                if (ResourceLeakDetector.class.isAssignableFrom(detectorClass)) {
                    return detectorClass.getConstructor(Class.class, int.class);
                } else {
                    logger.error("Class {} does not inherit from ResourceLeakDetector.", customLeakDetector);
                }
            } catch (Throwable t) {
                logger.error("Could not load custom resource leak detector class provided: {}",
                        customLeakDetector, t);
            }
            return null;
        }

        /**
         * 根据指定的 类型生成资源泄露对象  这个resource 对应的 是 ResourceLeakDetector 的哪个属性???
         * @param resource the resource class used to initialize the {@link ResourceLeakDetector}
         * @param samplingInterval the interval on which sampling takes place
         * @param maxActive This is deprecated and will be ignored.
         * @param <T>
         * @return
         */
        @SuppressWarnings("deprecation")
        @Override
        public <T> ResourceLeakDetector<T> newResourceLeakDetector(Class<T> resource, int samplingInterval,
                                                                   long maxActive) {
            if (obsoleteCustomClassConstructor != null) {
                try {
                    @SuppressWarnings("unchecked")
                    ResourceLeakDetector<T> leakDetector =
                            (ResourceLeakDetector<T>) obsoleteCustomClassConstructor.newInstance(
                                    resource, samplingInterval, maxActive);
                    logger.debug("Loaded custom ResourceLeakDetector: {}",
                            obsoleteCustomClassConstructor.getDeclaringClass().getName());
                    return leakDetector;
                } catch (Throwable t) {
                    logger.error(
                            "Could not load custom resource leak detector provided: {} with the given resource: {}",
                            obsoleteCustomClassConstructor.getDeclaringClass().getName(), resource, t);
                }
            }

            ResourceLeakDetector<T> resourceLeakDetector = new ResourceLeakDetector<T>(resource, samplingInterval,
                                                                                       maxActive);
            logger.debug("Loaded default ResourceLeakDetector: {}", resourceLeakDetector);
            return resourceLeakDetector;
        }

        @Override
        public <T> ResourceLeakDetector<T> newResourceLeakDetector(Class<T> resource, int samplingInterval) {
            //如果 存在用户自定义的 对象 就返回该对象
            if (customClassConstructor != null) {
                try {
                    @SuppressWarnings("unchecked")
                    ResourceLeakDetector<T> leakDetector =
                            (ResourceLeakDetector<T>) customClassConstructor.newInstance(resource, samplingInterval);
                    logger.debug("Loaded custom ResourceLeakDetector: {}",
                            customClassConstructor.getDeclaringClass().getName());
                    return leakDetector;
                } catch (Throwable t) {
                    logger.error(
                            "Could not load custom resource leak detector provided: {} with the given resource: {}",
                            customClassConstructor.getDeclaringClass().getName(), resource, t);
                }
            }

            ResourceLeakDetector<T> resourceLeakDetector = new ResourceLeakDetector<T>(resource, samplingInterval);
            logger.debug("Loaded default ResourceLeakDetector: {}", resourceLeakDetector);
            return resourceLeakDetector;
        }
    }
}

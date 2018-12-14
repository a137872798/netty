/*
 * Copyright 2013 The Netty Project
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

package io.netty.util.internal;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

/**
 * 类型匹配器
 */
public abstract class TypeParameterMatcher {

    /**
     * 一个 Noop 对象 默认 都是匹配成功
     */
    private static final TypeParameterMatcher NOOP = new TypeParameterMatcher() {
        @Override
        public boolean match(Object msg) {
            return true;
        }
    };

    /**
     * 获取指定类型的 匹配对象
     * @param parameterType
     * @return
     */
    public static TypeParameterMatcher get(final Class<?> parameterType) {
        final Map<Class<?>, TypeParameterMatcher> getCache =
                InternalThreadLocalMap.get().typeParameterMatcherGetCache();

        //尝试从缓存中获取
        TypeParameterMatcher matcher = getCache.get(parameterType);
        if (matcher == null) {
            //如果需要匹配的是 Object 类型 直接通过
            if (parameterType == Object.class) {
                matcher = NOOP;
            } else {
                //否则创建一个 反射匹配类型  该对象就是判断传入的参数 是否是 对应类型的 实例对象
                matcher = new ReflectiveMatcher(parameterType);
            }
            getCache.put(parameterType, matcher);
        }

        return matcher;
    }

    /**
     * 从缓存中尝试获取指定类型的 Type匹配器对象
     * 应该是一个二级缓存 第一级使用对象类型 第二级使用指定的 泛型名 例如 I 找到对应的 匹配对象
     * @param object 传入需要被匹配的目标对象
     * @param parametrizedSuperclass 需要 获取泛型类型的 目标类
     * @param typeParamName  这个是 handler 的 泛型参数类型  比如 I K V 这种
     * @return
     */
    public static TypeParameterMatcher find(
            final Object object, final Class<?> parametrizedSuperclass, final String typeParamName) {

        final Map<Class<?>, Map<String, TypeParameterMatcher>> findCache =
                InternalThreadLocalMap.get().typeParameterMatcherFindCache();
        //获取传入对象的类型
        final Class<?> thisClass = object.getClass();

        //从缓存中获取 该对象的 容器  也就是这个map 已经对应到某个类了 然后如果类是 有多个泛型参数的 比如 <K,V> 在存放2次
        Map<String, TypeParameterMatcher> map = findCache.get(thisClass);
        if (map == null) {
            map = new HashMap<String, TypeParameterMatcher>();
            findCache.put(thisClass, map);
        }

        //通过 typeParamName 作为 二级key 获取匹配器对象
        TypeParameterMatcher matcher = map.get(typeParamName);
        if (matcher == null) {
            //find0 确认到 泛型的实际类型
            matcher = get(find0(object, parametrizedSuperclass, typeParamName));
            map.put(typeParamName, matcher);
        }

        return matcher;
    }

    /**
     * 通过typeParamName 查询 对应的class 对象
     * @param object 需要被查询的对象
     * @param parametrizedSuperclass 父类对象
     * @param typeParamName 泛型类型  比如I
     * @return
     */
    private static Class<?> find0(
            final Object object, Class<?> parametrizedSuperclass, String typeParamName) {

        //获取对象的类型 这个一般是用户自定义的 handler
        final Class<?> thisClass = object.getClass();
        Class<?> currentClass = thisClass;
        for (;;) {
            //这个 父类 相当于是 限定了 泛型 的 查找范围 比如 在往上 就没有 泛型参数了
            if (currentClass.getSuperclass() == parametrizedSuperclass) {
                int typeParamIndex = -1;
                //获取该对象的  泛型信息 对应到 SimpleChannelInboundHandler 的 I
                TypeVariable<?>[] typeParams = currentClass.getSuperclass().getTypeParameters();
                //这里是 找到一个 能匹配上的泛型
                for (int i = 0; i < typeParams.length; i ++) {
                    //如果 泛型类型 能匹配上
                    if (typeParamName.equals(typeParams[i].getName())) {
                        //代表需要将 object 与哪个 泛型参数 匹配 因为 一个类可能有多个泛型参数
                        typeParamIndex = i;
                        break;
                    }
                }

                //泛型参数不合法
                if (typeParamIndex < 0) {
                    throw new IllegalStateException(
                            "unknown type parameter '" + typeParamName + "': " + parametrizedSuperclass);
                }

                //大概的意思是 这个能准确 返回 泛型的 真正类型
                Type genericSuperType = currentClass.getGenericSuperclass();
                if (!(genericSuperType instanceof ParameterizedType)) {
                    return Object.class;
                }

                //应该是能得到 父类的 真正泛型对象
                Type[] actualTypeParams = ((ParameterizedType) genericSuperType).getActualTypeArguments();

                //获取到真实类型
                Type actualTypeParam = actualTypeParams[typeParamIndex];
                if (actualTypeParam instanceof ParameterizedType) {
                    actualTypeParam = ((ParameterizedType) actualTypeParam).getRawType();
                }
                if (actualTypeParam instanceof Class) {
                    return (Class<?>) actualTypeParam;
                }
                if (actualTypeParam instanceof GenericArrayType) {
                    Type componentType = ((GenericArrayType) actualTypeParam).getGenericComponentType();
                    if (componentType instanceof ParameterizedType) {
                        componentType = ((ParameterizedType) componentType).getRawType();
                    }
                    if (componentType instanceof Class) {
                        return Array.newInstance((Class<?>) componentType, 0).getClass();
                    }
                }
                if (actualTypeParam instanceof TypeVariable) {
                    // Resolved type parameter points to another type parameter.
                    TypeVariable<?> v = (TypeVariable<?>) actualTypeParam;
                    currentClass = thisClass;
                    if (!(v.getGenericDeclaration() instanceof Class)) {
                        return Object.class;
                    }

                    parametrizedSuperclass = (Class<?>) v.getGenericDeclaration();
                    typeParamName = v.getName();
                    if (parametrizedSuperclass.isAssignableFrom(thisClass)) {
                        continue;
                    } else {
                        return Object.class;
                    }
                }

                return fail(thisClass, typeParamName);
            }
            //不存在 父类对象就抛出异常 看来是一级级往上找
            currentClass = currentClass.getSuperclass();
            if (currentClass == null) {
                return fail(thisClass, typeParamName);
            }
        }
    }

    private static Class<?> fail(Class<?> type, String typeParamName) {
        throw new IllegalStateException(
                "cannot determine the type of the type parameter '" + typeParamName + "': " + type);
    }

    public abstract boolean match(Object msg);

    /**
     * 通过判断 传入的 对象是否是 type 的实例对象 来确定是否 匹配成功
     */
    private static final class ReflectiveMatcher extends TypeParameterMatcher {
        private final Class<?> type;

        ReflectiveMatcher(Class<?> type) {
            this.type = type;
        }

        @Override
        public boolean match(Object msg) {
            return type.isInstance(msg);
        }
    }

    TypeParameterMatcher() { }
}

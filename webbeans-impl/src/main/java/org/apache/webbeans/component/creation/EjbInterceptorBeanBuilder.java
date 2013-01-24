/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.webbeans.component.creation;


import java.lang.reflect.Method;
import java.util.Map;

import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.InterceptionType;

import org.apache.webbeans.component.BeanAttributesImpl;
import org.apache.webbeans.component.EjbInterceptorBean;
import org.apache.webbeans.config.WebBeansContext;

/**
 * Bean builder for {@link org.apache.webbeans.component.InterceptorBean}s.
 */
public class EjbInterceptorBeanBuilder<T> extends InterceptorBeanBuilder<T, EjbInterceptorBean<T>>
{

    public EjbInterceptorBeanBuilder(WebBeansContext webBeansContext, AnnotatedType<T> annotatedType, BeanAttributesImpl<T> beanAttributes)
    {
        super(webBeansContext, annotatedType, beanAttributes);
    }

    public void defineEjbInterceptorRules()
    {
        checkInterceptorConditions();
        defineInterceptorRules();

    }

    public boolean isInterceptorEnabled()
    {
        return true;
    }

    @Override
    protected EjbInterceptorBean<T> createBean(Class<T> beanClass, boolean enabled, Map<InterceptionType, Method[]> interceptionMethods)
    {
        return new EjbInterceptorBean<T>(webBeansContext, getAnnotated(), getBeanAttributes(), beanClass, interceptionMethods);
    }
}

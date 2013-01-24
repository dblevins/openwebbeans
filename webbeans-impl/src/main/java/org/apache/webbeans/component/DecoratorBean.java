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
package org.apache.webbeans.component;

import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Decorator;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.Set;

import org.apache.webbeans.config.WebBeansContext;

/**
 * Decorator Bean implementation.
 */
public class DecoratorBean<T> extends InjectionTargetBean<T> implements Decorator<T>
{
    /*
     * Constructor of the Decorator component
     */
    private Constructor<T> constructor;

    /**
     * The Types the decorator itself implements
     */
    private Set<Type> decoratedTypes;

    /**
     * The Type of the &#064;Delegate injection point.
     */
    private Type delegateType;

    /**
     * The Qualifiers of the &#064;Delegate injection point.
     */
    private Set<Annotation> delegateQualifiers;


    public DecoratorBean(WebBeansContext webBeansContext,
                         WebBeansType webBeansType,
                         AnnotatedType<T> annotatedType,
                         BeanAttributesImpl<T> beanAttributes,
                         Class<T> beanClass)
    {
        super(webBeansContext, webBeansType, annotatedType, beanAttributes, beanClass);
    }

    public void setDecoratorInfo(Set<Type> decoratedTypes, Type delegateType, Set<Annotation> delegateQualifiers)
    {
        this.decoratedTypes = decoratedTypes;
        this.delegateType = delegateType;
        this.delegateQualifiers = delegateQualifiers;
    }

    @Override
    public Set<Type> getDecoratedTypes()
    {
        return decoratedTypes;
    }

    @Override
    public Type getDelegateType()
    {
        return delegateType;
    }

    @Override
    public Set<Annotation> getDelegateQualifiers()
    {
        return delegateQualifiers;
    }

    public void setConstructor(Constructor<T> constructor)
    {
        this.constructor = constructor;
    }

    public Constructor<T> getConstructor()
    {
        return constructor;
    }
}

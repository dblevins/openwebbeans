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

import static org.apache.webbeans.util.InjectionExceptionUtil.throwUnsatisfiedResolutionException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.Dependent;
import javax.enterprise.event.Observes;
import javax.enterprise.event.Reception;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.Specializes;
import javax.enterprise.inject.spi.AnnotatedConstructor;
import javax.enterprise.inject.spi.AnnotatedField;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedParameter;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.ObserverMethod;
import javax.enterprise.inject.spi.Producer;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.webbeans.annotation.AnnotationManager;
import org.apache.webbeans.component.BeanAttributesImpl;
import org.apache.webbeans.component.InjectionTargetBean;
import org.apache.webbeans.component.ProducerFieldBean;
import org.apache.webbeans.component.ProducerMethodBean;
import org.apache.webbeans.component.ResourceBean;
import org.apache.webbeans.component.ResourceProvider;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.container.InjectionResolver;
import org.apache.webbeans.exception.WebBeansConfigurationException;
import org.apache.webbeans.portable.InjectionTargetImpl;
import org.apache.webbeans.portable.ProducerFieldProducer;
import org.apache.webbeans.portable.ProducerMethodProducer;
import org.apache.webbeans.portable.ProviderBasedProxyProducer;
import org.apache.webbeans.spi.api.ResourceReference;
import org.apache.webbeans.util.AnnotationUtil;
import org.apache.webbeans.util.Asserts;
import org.apache.webbeans.util.ClassUtil;
import org.apache.webbeans.util.WebBeansUtil;


/**
 * Abstract implementation of {@link AbstractBeanBuilder}.
 * 
 * @version $Rev$ $Date$
 *
 * @param <T> bean class type
 */
public abstract class AbstractInjectionTargetBeanBuilder<T, I extends InjectionTargetBean<T>> extends AbstractBeanBuilder<T, AnnotatedType<T>, I>
{    
    
    private boolean enabled = true;
    protected WebBeansContext webBeansContext;

    /**
     * Creates a new instance.
     * 
     */
    public AbstractInjectionTargetBeanBuilder(WebBeansContext webBeansContext, AnnotatedType<T> annotatedType, BeanAttributesImpl<T> beanAttributes)
    {
        super(webBeansContext, annotatedType, beanAttributes);
        this.webBeansContext = webBeansContext;
    }

    protected AnnotatedType<? super T> getSuperAnnotated()
    {
        Class<? super T> superclass = getAnnotated().getJavaClass().getSuperclass();
        if (superclass == null)
        {
            return null;
        }
        return webBeansContext.getAnnotatedElementFactory().getAnnotatedType(superclass);
    }
    
    protected AnnotatedConstructor<T> getBeanConstructor()
    {
        Asserts.assertNotNull(getAnnotated(),"Type is null");
        AnnotatedConstructor<T> result = null;

        Set<AnnotatedConstructor<T>> annConsts = getAnnotated().getConstructors();
        if(annConsts != null)
        {
            boolean found = false;
            boolean noParamConsIsDefined = false;
            for(AnnotatedConstructor<T> annConst : annConsts)
            {
                if(annConst.isAnnotationPresent(Inject.class))
                {
                    if (found)
                    {
                        throw new WebBeansConfigurationException("There are more than one constructor with @Inject annotation in annotation type : "
                                + getAnnotated());
                    }

                    found = true;
                    result = annConst;
                }
                else
                {
                    if(!found && !noParamConsIsDefined)
                    {
                        List<AnnotatedParameter<T>> parameters = annConst.getParameters();
                        if(parameters != null && parameters.isEmpty())
                        {
                            result = annConst;
                            noParamConsIsDefined = true;
                        }
                    }
                }
            }
        }

        if (result == null)
        {
            throw new WebBeansConfigurationException("No constructor is found for the annotated type : " + getAnnotated());
        }

        List<AnnotatedParameter<T>> parameters = result.getParameters();
        for(AnnotatedParameter<T> parameter : parameters)
        {
            if (parameter.isAnnotationPresent(Disposes.class))
            {
                throw new WebBeansConfigurationException("Constructor parameter annotations can not contain @Disposes annotation in annotated constructor : "
                        + result);
            }

            if(parameter.isAnnotationPresent(Observes.class))
            {
                throw new WebBeansConfigurationException("Constructor parameter annotations can not contain @Observes annotation in annotated constructor : " + result);
            }

        }

        return result;
    }

    public void defineDisposalMethods()
    {
        Set<AnnotatedMethod<? super T>> annotatedMethods = getAnnotated().getMethods();
        
        for (AnnotatedMethod<? super T> annotatedMethod : annotatedMethods)
        {            
            for (AnnotatedParameter<? super T> annotatedParameter : annotatedMethod.getParameters())
            {
                if (annotatedParameter.isAnnotationPresent(Disposes.class))
                {
                    addInjectionPoint(annotatedMethod);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void validateDisposalMethods(InjectionTargetBean<T> bean)
    {
        final AnnotationManager annotationManager = webBeansContext.getAnnotationManager();
        Set<AnnotatedMethod<? super T>> annotatedMethods = getAnnotated().getMethods();    
        ProducerMethodBean<?> previous = null;
        for (InjectionPoint injectionPoint : bean.getInjectionPoints())
        {
            if (injectionPoint.getAnnotated().isAnnotationPresent(Disposes.class))
            {
                AnnotatedParameter<T> annotatedParameter = (AnnotatedParameter<T>) injectionPoint.getAnnotated();
                AnnotatedMethod<T> annotatedMethod = (AnnotatedMethod<T>) annotatedParameter.getDeclaringCallable();
                Method declaredMethod = annotatedMethod.getJavaMember();
                checkProducerMethodDisposal(annotatedMethod);
                Type type = AnnotationUtil.getAnnotatedMethodFirstParameterWithAnnotation(annotatedMethod, Disposes.class);
                Annotation[] annot = annotationManager.getAnnotatedMethodFirstParameterQualifierWithGivenAnnotation(annotatedMethod, Disposes.class);

                InjectionResolver injectionResolver = webBeansContext.getBeanManagerImpl().getInjectionResolver();

                Set<Bean<?>> set = injectionResolver.implResolveByType(type, annot);
                if (set.isEmpty())
                {
                    throwUnsatisfiedResolutionException(type, declaredMethod, annot);
                }
                
                Bean<?> foundBean = set.iterator().next();
                ProducerMethodBean<?> pr = null;

                if (foundBean == null || !(foundBean instanceof ProducerMethodBean))
                {
                    throwUnsatisfiedResolutionException(annotatedMethod.getDeclaringType().getJavaClass(), declaredMethod, annot);
                }

                pr = (ProducerMethodBean<?>) foundBean;

                if (previous == null)
                {
                    previous = pr;
                }
                else
                {
                    // multiple same producer
                    if (previous.equals(pr))
                    {
                        throw new WebBeansConfigurationException("There are multiple disposal method for the producer method : " + pr.getCreatorMethod().getName() + " in class : "
                                                                 + annotatedMethod.getDeclaringType().getJavaClass());
                    }
                }

                Method producerMethod = pr.getCreatorMethod();
                //Disposer methods and producer methods must be in the same class
                if(!producerMethod.getDeclaringClass().getName().equals(declaredMethod.getDeclaringClass().getName()))
                {
                    throw new WebBeansConfigurationException("Producer method component of the disposal method : " + declaredMethod.getName() + " in class : "
                                                             + annotatedMethod.getDeclaringType().getJavaClass() + " must be in the same class!");
                }
                
                pr.setDisposalMethod(declaredMethod);
                Producer<?> producer = pr.getProducer();
                if (producer instanceof ProducerMethodProducer)
                {
                    ((ProducerMethodProducer)producer).setDisposalMethod(annotatedMethod);
                }
            }
        }
    }

    /**
     * CheckProducerMethodDisposal.
     * @param annotatedMethod disposal method
     */
    private void checkProducerMethodDisposal(AnnotatedMethod<T> annotatedMethod)
    {
        List<AnnotatedParameter<T>> parameters = annotatedMethod.getParameters();
        boolean found = false;
        for(AnnotatedParameter<T> parameter : parameters)
        {
            if(parameter.isAnnotationPresent(Disposes.class))
            {
                if(found)
                {
                    throw new WebBeansConfigurationException("Error in definining disposal method of annotated method : " + annotatedMethod
                            + ". Multiple disposes annotation.");
                }
                found = true;
            }
        }
        
        if(annotatedMethod.isAnnotationPresent(Inject.class) 
                || AnnotationUtil.hasAnnotatedMethodParameterAnnotation(annotatedMethod, Observes.class) 
                || annotatedMethod.isAnnotationPresent(Produces.class))
        {
            throw new WebBeansConfigurationException("Error in definining disposal method of annotated method : " + annotatedMethod
                    + ". Disposal methods  can not be annotated with" + " @Initializer/@Destructor/@Produces annotation or has a parameter annotated with @Observes.");
        }        
    }

    /**
     * {@inheritDoc}
     */
    public void defineInjectedFields()
    {
        AnnotationManager annotationManager = webBeansContext.getAnnotationManager();

        Set<AnnotatedField<? super T>> annotatedFields = getAnnotated().getFields();
        for(AnnotatedField<? super T> annotatedField: annotatedFields)
        {
            if(Modifier.isPublic(annotatedField.getJavaMember().getModifiers()) && !annotatedField.isStatic())
            {
                if(webBeansContext.getBeanManagerImpl().isNormalScope(getBeanAttributes().getScope()))
                {
                    throw new WebBeansConfigurationException("If bean has a public field, bean scope must be defined as @Scope. Bean is : "
                            + getBeanType().getName());
                }
            }                
            
            if(!annotatedField.isAnnotationPresent(Inject.class))
            {
                continue;
            }

            if (annotatedField.isAnnotationPresent(Produces.class))
            {
                throw new WebBeansConfigurationException("Injection fields can not be annotated with @Produces");
            }
            
            Field field = annotatedField.getJavaMember();
            Annotation[] anns = AnnotationUtil.asArray(annotatedField.getAnnotations());
            if(Modifier.isPublic(field.getModifiers()))
            {
                if(!getBeanAttributes().getScope().equals(Dependent.class))
                {
                    throw new WebBeansConfigurationException("Error in annotated field : " + annotatedField
                                                    +" while definining injected field. If bean has a public modifier injection point, bean scope must be defined as @Dependent");
                }
            }

            Annotation[] qualifierAnns = annotationManager.getQualifierAnnotations(anns);

            if (qualifierAnns.length > 0)
            {
                if (qualifierAnns.length > 0)
                {
                    annotationManager.checkForNewQualifierForDeployment(annotatedField.getBaseType(), annotatedField.getDeclaringType().getJavaClass(), field.getName(), anns);
                }

                int mod = field.getModifiers();
                
                if (!Modifier.isStatic(mod) && !Modifier.isFinal(mod))
                {
                    addInjectionPoint(annotatedField);
                }
            }                                    
        }
    }

    /**
     * {@inheritDoc}
     */
    public void defineInjectedMethods()
    {
        Set<AnnotatedMethod<? super T>> annotatedMethods = getAnnotated().getMethods();
        
        for (AnnotatedMethod<? super T> annotatedMethod : annotatedMethods)
        {            
            boolean isInitializer = annotatedMethod.isAnnotationPresent(Inject.class);            

            if (isInitializer)
            {
                //Do not support static
                if(annotatedMethod.isStatic())
                {
                    continue;
                }
                
                checkForInjectedInitializerMethod((AnnotatedMethod<T>)annotatedMethod);
            }
            else
            {
                continue;
            }

            Method method = annotatedMethod.getJavaMember();
            
            if (!Modifier.isStatic(method.getModifiers()))
            {
                addInjectionPoint(annotatedMethod);
            }
        }
    }

    /**
     * add the definitions for a &#x0040;Initializer method.
     */
    private void checkForInjectedInitializerMethod(AnnotatedMethod<T> annotatedMethod)
    {
        Method method = annotatedMethod.getJavaMember();
        
        TypeVariable<?>[] args = method.getTypeParameters();
        if(args.length > 0)
        {
            throw new WebBeansConfigurationException("Error in defining injected methods in annotated method : " + annotatedMethod+ 
                    ". Reason : Initializer methods must not be generic.");
        }
        
        if (annotatedMethod.isAnnotationPresent(Produces.class))
        {
            throw new WebBeansConfigurationException("Error in defining injected methods in annotated method : " + annotatedMethod+ 
            ". Reason : Initializer method can not be annotated with @Produces.");
        
        }

        AnnotationManager annotationManager = webBeansContext.getAnnotationManager();

        List<AnnotatedParameter<T>> annotatedParameters = annotatedMethod.getParameters();
        for (AnnotatedParameter<T> annotatedParameter : annotatedParameters)
        {
            annotationManager.checkForNewQualifierForDeployment(annotatedParameter.getBaseType(), annotatedMethod.getDeclaringType().getJavaClass(),
                    method.getName(), AnnotationUtil.asArray(annotatedParameter.getAnnotations()));

            if(annotatedParameter.isAnnotationPresent(Disposes.class) ||
                    annotatedParameter.isAnnotationPresent(Observes.class))
            {
                throw new WebBeansConfigurationException("Error in defining injected methods in annotated method : " + annotatedMethod+ 
                ". Reason : Initializer method parameters does not contain @Observes or @Dispose annotations.");
                
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public Set<ObserverMethod<?>> defineObserverMethods(InjectionTargetBean<T> bean)
    {   
        Set<ObserverMethod<?>> definedObservers = new HashSet<ObserverMethod<?>>();
        Set<AnnotatedMethod<? super T>> annotatedMethods = getAnnotated().getMethods();    
        for (AnnotatedMethod<? super T> annotatedMethod : annotatedMethods)
        {
            AnnotatedMethod<T> annt = (AnnotatedMethod<T>)annotatedMethod;
            List<AnnotatedParameter<T>> parameters = annt.getParameters();
            boolean found = false;
            for(AnnotatedParameter<T> parameter : parameters)
            {
                if(parameter.isAnnotationPresent(Observes.class))
                {
                    found = true;
                    break;
                }
            }
            
            if(found)
            {
                checkObserverMethodConditions((AnnotatedMethod<T>) annotatedMethod, annotatedMethod.getDeclaringType().getJavaClass());
                if (bean.getScope().equals(Dependent.class))
                {
                    //Check Reception
                     AnnotationUtil.getAnnotatedMethodFirstParameterWithAnnotation(annotatedMethod, Observes.class);
                    
                     Observes observes = AnnotationUtil.getAnnotatedMethodFirstParameterAnnotation(annotatedMethod, Observes.class);
                     Reception reception = observes.notifyObserver();
                     if(reception.equals(Reception.IF_EXISTS))
                     {
                         throw new WebBeansConfigurationException("Dependent Bean : " + getBeanType() + " can not define observer method with @Receiver = IF_EXIST");
                     }
                }
                
                //Add injection point data
                addInjectionPoint(annotatedMethod);
                
                //Looking for ObserverMethod
                ObserverMethod<?> definedObserver = webBeansContext.getBeanManagerImpl().getNotificationManager().getObservableMethodForAnnotatedMethod(annotatedMethod, bean);
                if(definedObserver != null)
                {
                    definedObservers.add(definedObserver);
                }
            }
        }
        
        return definedObservers;
    }

    private void checkObserverMethodConditions(AnnotatedMethod<T> annotatedMethod, Class<?> clazz)
    {
        Asserts.assertNotNull(annotatedMethod, "annotatedMethod parameter can not be null");
        Asserts.nullCheckForClass(clazz);
        
        Method candidateObserverMethod = annotatedMethod.getJavaMember();
        
        if (AnnotationUtil.hasAnnotatedMethodMultipleParameterAnnotation(annotatedMethod, Observes.class))
        {
            throw new WebBeansConfigurationException("Observer method : " + candidateObserverMethod.getName() + " in class : " + clazz.getName()
                                                     + " can not define two parameters with annotated @Observes");
        }

        if (annotatedMethod.isAnnotationPresent(Produces.class) 
                || annotatedMethod.isAnnotationPresent(Inject.class))
        {
            throw new WebBeansConfigurationException("Observer method : " + candidateObserverMethod.getName() + " in class : " + clazz.getName()
                                                     + " can not annotated with annotation in the list {@Produces, @Initializer, @Destructor}");

        }

        if (AnnotationUtil.hasAnnotatedMethodParameterAnnotation(annotatedMethod, Disposes.class))
        {
            throw new WebBeansConfigurationException("Observer method : " + candidateObserverMethod.getName() + " in class : "
                                                     + clazz.getName() + " can not annotated with annotation @Disposes");
        }                
    }

    /**
     * {@inheritDoc}
     */
    public Set<ProducerFieldBean<?>> defineProducerFields(InjectionTargetBean<T> bean)
    {
        Set<ProducerFieldBean<?>> producerBeans = new HashSet<ProducerFieldBean<?>>();
        Set<AnnotatedField<? super T>> annotatedFields = getAnnotated().getFields();        
        for(AnnotatedField<? super T> annotatedField: annotatedFields)
        {
            if(annotatedField.isAnnotationPresent(Produces.class) && annotatedField.getDeclaringType().equals(getAnnotated()))
            {
                Type genericType = annotatedField.getBaseType();
                
                if(ClassUtil.isTypeVariable(genericType))
                {
                    throw new WebBeansConfigurationException("Producer annotated field : " + annotatedField + " can not be Wildcard type or Type variable");
                }
                if(ClassUtil.isParametrizedType(genericType))
                {
                    if(!ClassUtil.checkParametrizedType((ParameterizedType)genericType))
                    {
                        throw new WebBeansConfigurationException("Producer annotated field : " + annotatedField + " can not be Wildcard type or Type variable");
                    }
                }
                
                Annotation[] anns = AnnotationUtil.asArray(annotatedField.getAnnotations());
                Field field = annotatedField.getJavaMember();
                
                //Producer field for resource
                Annotation resourceAnnotation = AnnotationUtil.hasOwbInjectableResource(anns);                
                //Producer field for resource
                if(resourceAnnotation != null)
                {                    
                    //Check for valid resource annotation
                    //WebBeansUtil.checkForValidResources(annotatedField.getDeclaringType().getJavaClass(), field.getType(), field.getName(), anns);
                    if(!Modifier.isStatic(field.getModifiers()))
                    {
                        ResourceReference<T, Annotation> resourceRef = new ResourceReference<T, Annotation>(getBeanType(), field.getName(),
                                                                                                            (Class<T>)field.getType(), resourceAnnotation);
                        
                        //Can not define EL name
                        if(annotatedField.isAnnotationPresent(Named.class))
                        {
                            throw new WebBeansConfigurationException("Resource producer annotated field : " + annotatedField + " can not define EL name");
                        }
                        
                        BeanAttributesImpl<T> beanAttributes = BeanAttributesBuilder.forContext(webBeansContext).newBeanAttibutes((AnnotatedField<T>)annotatedField).build();
                        ResourceBeanBuilder<T, Annotation> resourceBeanCreator
                            = new ResourceBeanBuilder<T, Annotation>(bean, resourceRef, annotatedField, beanAttributes);
                        ResourceBean<T, Annotation> resourceBean = resourceBeanCreator.getBean();
                        ResourceProvider<T> resourceProvider = new ResourceProvider<T>(resourceBean.getReference(), webBeansContext);
                        resourceBean.setProducer(new ProviderBasedProxyProducer<T>(webBeansContext, resourceBean.getReturnType(), resourceProvider));


                        resourceBean.setProducerField(field);
                        
                        producerBeans.add(resourceBean);                                            
                    }
                }
                else
                {
                    BeanAttributesImpl<T> beanAttributes = BeanAttributesBuilder.forContext(webBeansContext).newBeanAttibutes((AnnotatedField<T>)annotatedField).build();
                    ProducerFieldBeanBuilder<T, ProducerFieldBean<T>> producerFieldBeanCreator
                        = new ProducerFieldBeanBuilder<T, ProducerFieldBean<T>>(bean, annotatedField, beanAttributes);
                    producerFieldBeanCreator.checkUnproxiableApiType();
                    ProducerFieldBean<T> producerFieldBean = producerFieldBeanCreator.getBean();
                    producerFieldBean.setProducer(new ProducerFieldProducer(bean, annotatedField, producerFieldBean.getInjectionPoints()));
                    producerFieldBean.setProducerField(field);
                    
                    if (producerFieldBean.getReturnType().isPrimitive())
                    {
                        producerFieldBean.setNullable(false);
                    }                    

                    webBeansContext.getWebBeansUtil().setBeanEnableFlagForProducerBean(bean, producerFieldBean, anns);
                    WebBeansUtil.checkProducerGenericType(producerFieldBean, annotatedField.getJavaMember());
                    
                    producerBeans.add(producerFieldBean);
                }
            }
        }
        
        return producerBeans;
    }

    /**
     * {@inheritDoc}
     */
    public Set<ProducerMethodBean<?>> defineProducerMethods(InjectionTargetBean<T> bean)
    {
        Set<ProducerMethodBean<?>> producerBeans = new HashSet<ProducerMethodBean<?>>();
        Set<AnnotatedMethod<? super T>> annotatedMethods = getAnnotated().getMethods();
        
        for(AnnotatedMethod<? super T> annotatedMethod: annotatedMethods)
        {
            if(annotatedMethod.isAnnotationPresent(Produces.class) && annotatedMethod.getDeclaringType().equals(getAnnotated()))
            {
                checkProducerMethodForDeployment(annotatedMethod);
                boolean specialize = false;
                if(annotatedMethod.isAnnotationPresent(Specializes.class))
                {
                    if (annotatedMethod.isStatic())
                    {
                        throw new WebBeansConfigurationException("Specializing annotated producer method : " + annotatedMethod + " can not be static");
                    }
                    
                    specialize = true;
                }
                
                BeanAttributesImpl<T> beanAttributes = BeanAttributesBuilder.forContext(webBeansContext).newBeanAttibutes((AnnotatedMethod<T>)annotatedMethod).build();
                ProducerMethodBeanBuilder<T> producerMethodBeanCreator = new ProducerMethodBeanBuilder<T>(bean, annotatedMethod, beanAttributes);
                
                producerMethodBeanCreator.checkUnproxiableApiType();

                producerMethodBeanCreator.addInjectionPoint(annotatedMethod);
                ProducerMethodBean<T> producerMethodBean = producerMethodBeanCreator.getBean();
                
                if(specialize)
                {
                    producerMethodBeanCreator.configureProducerSpecialization(producerMethodBean, (AnnotatedMethod<T>) annotatedMethod);
                }
                ProducerMethodProducerBuilder producerBuilder = new ProducerMethodProducerBuilder(producerMethodBean);
                producerMethodBean.setProducer(producerBuilder.build(annotatedMethod));
                producerMethodBean.setCreatorMethod(annotatedMethod.getJavaMember());
                if (ClassUtil.getClass(annotatedMethod.getBaseType()).isPrimitive())
                {
                    producerMethodBean.setNullable(false);
                }
                
                webBeansContext.getWebBeansUtil().setBeanEnableFlagForProducerBean(bean,
                        producerMethodBean,
                        AnnotationUtil.asArray(annotatedMethod.getAnnotations()));
                WebBeansUtil.checkProducerGenericType(producerMethodBean, annotatedMethod.getJavaMember());
                producerBeans.add(producerMethodBean);
                
            }
            
        }
        
        return producerBeans;
    }

    /**
     * Check producer method is ok for deployment.
     * 
     * @param annotatedMethod producer method
     */
    private void checkProducerMethodForDeployment(AnnotatedMethod<? super T> annotatedMethod)
    {
        Asserts.assertNotNull(annotatedMethod, "annotatedMethod argument can not be null");

        if (annotatedMethod.isAnnotationPresent(Inject.class) || 
                annotatedMethod.isAnnotationPresent(Disposes.class) ||  
                annotatedMethod.isAnnotationPresent(Observes.class))
        {
            throw new WebBeansConfigurationException("Producer annotated method : " + annotatedMethod + " can not be annotated with"
                                                     + " @Initializer/@Destructor annotation or has a parameter annotated with @Disposes/@Observes");
        }
    }

    protected InjectionTarget<T> buildInjectionTarget(AnnotatedType<T> annotatedType,
                                                      Set<InjectionPoint> points,
                                                      WebBeansContext webBeansContext,
                                                      List<AnnotatedMethod<?>> postConstructMethods,
                                                      List<AnnotatedMethod<?>> preDestroyMethods)
    {
        InjectionTargetImpl<T> injectionTarget = new InjectionTargetImpl<T>(annotatedType, points, webBeansContext, postConstructMethods, preDestroyMethods);

        return injectionTarget;
    }

    protected abstract I createBean(Class<T> beanClass, boolean enabled);

    @Override
    protected final I createBean(Class<T> beanClass)
    {
        I bean =  createBean(beanClass, enabled);

        //X TODO hack to set the InjectionTarget
        InjectionTarget<T> injectionTarget
                = buildInjectionTarget(bean.getAnnotatedType(), bean.getInjectionPoints(), webBeansContext, getPostConstructMethods(), getPreDestroyMethods());
        bean.setProducer(injectionTarget);

        return bean;
    }

    protected List<AnnotatedMethod<?>> getPostConstructMethods()
    {
        List<AnnotatedMethod<?>> postConstructMethods = new ArrayList<AnnotatedMethod<?>>();
        collectPostConstructMethods(getAnnotated().getJavaClass(), postConstructMethods);
        return postConstructMethods;
    }

    private void collectPostConstructMethods(Class<?> type, List<AnnotatedMethod<?>> postConstructMethods)
    {
        if (type == null)
        {
            return;
        }
        collectPostConstructMethods(type.getSuperclass(), postConstructMethods);
        for (AnnotatedMethod<?> annotatedMethod: getAnnotated().getMethods())
        {
            if (annotatedMethod.getJavaMember().getDeclaringClass() == type
                && annotatedMethod.isAnnotationPresent(PostConstruct.class)
                && annotatedMethod.getParameters().isEmpty())
            {
                postConstructMethods.add(annotatedMethod);
            }
        }
    }

    protected List<AnnotatedMethod<?>> getPreDestroyMethods()
    {
        List<AnnotatedMethod<?>> preDestroyMethods = new ArrayList<AnnotatedMethod<?>>();
        collectPreDestroyMethods(getAnnotated().getJavaClass(), preDestroyMethods);
        return preDestroyMethods;
    }

    private void collectPreDestroyMethods(Class<?> type, List<AnnotatedMethod<?>> preDestroyMethods)
    {
        if (type == null)
        {
            return;
        }
        collectPreDestroyMethods(type.getSuperclass(), preDestroyMethods);
        for (AnnotatedMethod<?> annotatedMethod: getAnnotated().getMethods())
        {
            if (annotatedMethod.getJavaMember().getDeclaringClass() == type
                && annotatedMethod.isAnnotationPresent(PreDestroy.class)
                && annotatedMethod.getParameters().isEmpty())
            {
                preDestroyMethods.add(annotatedMethod);
            }
        }
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void defineEnabled()
    {
        enabled = webBeansContext.getWebBeansUtil().isBeanEnabled(getAnnotated(), getBeanType(), getBeanAttributes().getStereotypes());
    }

    @Override
    protected Class<T> getBeanType()
    {
        return getAnnotated().getJavaClass();
    }
}

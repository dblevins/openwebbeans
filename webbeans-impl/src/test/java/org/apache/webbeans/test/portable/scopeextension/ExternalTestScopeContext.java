/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.webbeans.test.portable.scopeextension;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

import javax.enterprise.context.spi.Context;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.BeforeShutdown;
import javax.enterprise.event.Observes;

public class ExternalTestScopeContext implements Context 
{
    public ExternalTestScopeContext(boolean active)
    {
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Contextual<T> component) 
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T get(Contextual<T> component, CreationalContext<T> creationalContext) 
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Class<? extends Annotation> getScope() 
    {
        return ExternalTestScoped.class;
    }

    @Override
    public boolean isActive()
    {
        throw new UnsupportedOperationException();
    }
    
    public void endContext(@Observes BeforeShutdown beforeShutdown)
    {
        // a real world extension would destroy all contextual instances here
    }

}

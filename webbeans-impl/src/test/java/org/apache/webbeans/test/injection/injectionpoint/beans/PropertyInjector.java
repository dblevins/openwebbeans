/*
 * Copyright 2012 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.webbeans.test.injection.injectionpoint.beans;

import javax.inject.Inject;
import javax.inject.Named;

@Named("org.apache.webbeans.test.injection.injectionpoint.beans.PropertyInjector")
public class PropertyInjector {

    @Inject
    private DataTransformer dataTransformer;
    
    @Inject
    @PropertyHolder
    private String anotherVarName;
    
    @Inject
    @PropertyHolder
    private String ldapHost;
    
    @Inject
    private MyContainer nested;
    
    public String getAnotherVarName() {
        return anotherVarName;
    }

    public DataTransformer getDataTransformer() {
        return dataTransformer;
    }


    public String getLdapHost() {
        return ldapHost;
    }


    public MyContainer getNested() {
        return nested;
    }
}
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
package org.apache.webbeans.lifecycle;

import java.lang.annotation.Annotation;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.enterprise.inject.spi.BeanManager;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletRequestEvent;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.jsp.JspApplicationContext;
import javax.servlet.jsp.JspFactory;

import org.apache.webbeans.WebBeansConstants;
import org.apache.webbeans.config.OpenWebBeansConfiguration;
import org.apache.webbeans.config.BeansDeployer;
import org.apache.webbeans.config.WebBeansFinder;
import org.apache.webbeans.config.OWBLogConst;
import org.apache.webbeans.container.BeanManagerImpl;
import org.apache.webbeans.context.ContextFactory;
import org.apache.webbeans.conversation.ConversationManager;
import org.apache.webbeans.el.WebBeansELResolver;
import org.apache.webbeans.exception.WebBeansException;
import org.apache.webbeans.logger.WebBeansLogger;
import org.apache.webbeans.plugins.PluginLoader;
import org.apache.webbeans.portable.events.ExtensionLoader;
import org.apache.webbeans.portable.events.discovery.BeforeShutdownImpl;
import org.apache.webbeans.servlet.WebBeansConfigurationListener;
import org.apache.webbeans.spi.ContainerLifecycle;
import org.apache.webbeans.spi.JNDIService;
import org.apache.webbeans.spi.ScannerService;
import org.apache.webbeans.spi.ServiceLoader;
import org.apache.webbeans.xml.WebBeansXMLConfigurator;

/**
 * Manages container lifecycle.
 * 
 * <p>
 * Behaves according to the request, session, and application
 * contexts of the web application. 
 * </p>
 * 
 * @version $Rev$ $Date$
 * @see WebBeansConfigurationListener
 */
public final class DefaultLifecycle implements ContainerLifecycle
{
	//Logger instance
    private static final WebBeansLogger logger = WebBeansLogger.getLogger(DefaultLifecycle.class);

    /**Manages unused conversations*/
    private ScheduledExecutorService service = null;

    /**Discover bean classes*/
    private ScannerService discovery = null;

    /**Deploy discovered beans*/
    private final BeansDeployer deployer;

    /**XML discovery. */
    //XML discovery is removed from the specification. It is here for next revisions of spec.
    private final WebBeansXMLConfigurator xmlDeployer;
    
    /**Using for lookup operations*/
    private final JNDIService jndiService;
    
    /**Root container.*/
    //Activities are removed from the specification.
    private final BeanManagerImpl rootManager;

    /**
     * Creates a new lifecycle instance and initializes
     * the instance variables.
     */
    public DefaultLifecycle()
    {
        this.rootManager = (BeanManagerImpl) WebBeansFinder.getSingletonInstance(WebBeansFinder.SINGLETON_MANAGER);
        this.xmlDeployer = new WebBeansXMLConfigurator();
        this.deployer = new BeansDeployer(xmlDeployer);
        this.jndiService = ServiceLoader.getService(JNDIService.class);
        
        init();
    }

    public void init()
    {
        rootManager.setXMLConfigurator(this.xmlDeployer);        
    }
    
    public void requestStarted(ServletRequestEvent event)
    {
        logger.debug("Starting a new request : ", new Object[]{event.getServletRequest().getRemoteAddr()});
        
        ContextFactory.initializeThreadLocals();
        
        //Init Request Context
        ContextFactory.initRequestContext(event);
        
        //Session Conext Must be Active
        Object request = event.getServletRequest();
        if(request instanceof HttpServletRequest)
        {
            HttpServletRequest httpRequest = (HttpServletRequest)request;
            HttpSession currentSession = httpRequest.getSession(false);
            if(currentSession == null)
            {
                //To activate session context
            	try 
            	{
            		httpRequest.getSession();
            	}
            	catch(Exception e) {
            		logger.error(OWBLogConst.ERROR_0013, e);
            	}
            }
        }
    }

    public void requestEnded(ServletRequestEvent event)
    {
    	logger.debug("Destroying a request : ", new Object[]{event.getServletRequest().getRemoteAddr()});
        ContextFactory.destroyRequestContext((HttpServletRequest) event.getServletRequest());
    }

    public void sessionStarted(HttpSessionEvent event)
    {
        logger.debug("Starting a session with session id : ", new Object[]{event.getSession().getId()});
        ContextFactory.initSessionContext(event.getSession());
    }

    public void sessionEnded(HttpSessionEvent event)
    {
    	logger.debug("Destroying a session with session id : ", new Object[]{event.getSession().getId()});
        ContextFactory.destroySessionContext(event.getSession());

        ConversationManager conversationManager = ConversationManager.getInstance();
        conversationManager.destroyConversationContextWithSessionId(event.getSession().getId());
    }

    public void applicationStarted(Object startupObject)
    {
        ServletContext servletContext = null;
        if(startupObject != null)
        {
            if(startupObject instanceof ServletContextEvent)
            {
                servletContext = ((ServletContextEvent) startupObject).getServletContext();
            }
            else
            {
                throw new WebBeansException(logger.getTokenString(OWBLogConst.EXCEPT_0001));
            }
        }
        
        // Initalize Application Context
        logger.debug("OpenWebBeans Container is starting.");
        
        long begin = System.currentTimeMillis();

        //Application Context initialization
        ContextFactory.initApplicationContext(servletContext);
        
        //Singleton context
        ContextFactory.initSingletonContext(servletContext);

        this.discovery = ServiceLoader.getService(ScannerService.class);
        this.discovery.init(servletContext);

        // load all optional plugins
        PluginLoader.getInstance().startUp();

        String strDelay = OpenWebBeansConfiguration.getInstance().getProperty(OpenWebBeansConfiguration.CONVERSATION_PERIODIC_DELAY,"150000");
        long delay = Long.parseLong(strDelay);
        
        service = Executors.newScheduledThreadPool(1);
        service.scheduleWithFixedDelay(new ConversationCleaner(), delay, delay, TimeUnit.MILLISECONDS);

        logger.debug("Scanning classpaths for beans artifacts.");

        this.discovery.scan();

        logger.debug("Deploying scanned beans.");

        deployer.deploy(this.discovery);
        
        //Application is configured as JSP
        if(OpenWebBeansConfiguration.getInstance().isJspApplication())
        {
            logger.debug("Application is configured as JSP. Adding EL Resolver.");
            
            JspApplicationContext applicationCtx = JspFactory.getDefaultFactory().getJspApplicationContext(servletContext);
            applicationCtx.addELResolver(new WebBeansELResolver());            
        }
        
        long end = System.currentTimeMillis();
        
        logger.info(OWBLogConst.INFO_0002, new Object[]{Long.toString(end - begin)});
    }

    public void applicationEnded(Object endObject)
    {
        ServletContext servletContext = null;
        if(endObject != null)
        {
            if(endObject instanceof ServletContextEvent)
            {
                servletContext = ((ServletContextEvent) endObject).getServletContext();
            }
            else
            {
                throw new WebBeansException(logger.getTokenString(OWBLogConst.EXCEPT_0002));
            }
        }
        logger.debug("OpenWebBeans Container is stopping.");

        //Fire shut down
        this.rootManager.fireEvent(new BeforeShutdownImpl(), new Annotation[0]);
                
        service.shutdownNow();

        ContextFactory.destroyApplicationContext(servletContext);
        
        ContextFactory.destroySingletonContext(servletContext);

        jndiService.unbind(WebBeansConstants.WEB_BEANS_MANAGER_JNDI_NAME);

        // finally free all plugin resources
        PluginLoader.getInstance().shutDown();
        
        //Clear extensions
        ExtensionLoader.getInstance().clear();
        
        //Clear singleton list
        WebBeansFinder.clearInstances();
                
        logger.info(OWBLogConst.INFO_0003, new Object[]{servletContext != null ? servletContext.getContextPath() : null});
    }
    
    public void sessionPassivated(HttpSessionEvent event)
    {
        logger.info(OWBLogConst.INFO_0004, new Object[]{event.getSession().getId()});
    }

    public void sessionActivated(HttpSessionEvent event)
    {
        logger.info(OWBLogConst.INFO_0005, new Object[]{event.getSession().getId()});
    }

    private static class ConversationCleaner implements Runnable
    {
        public ConversationCleaner()
        {

        }

        public void run()
        {
            ConversationManager.getInstance().destroyWithRespectToTimout();

        }
    }

    @Override
    public BeanManager getBeanManager()
    {
        return this.rootManager;
    }

    @Override
    public void init(Properties properties)
    {
        
    }

    @Override
    public void start(Object startupObject) throws Exception
    {
        applicationStarted(startupObject);
    }

    @Override
    public void stop(Object endObject)
    {
        applicationEnded(endObject);
    }

}

package io.pivotal.poc.gemfire.gtx.jndi;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.ClassUtils;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;
import javax.naming.spi.InitialContextFactoryBuilder;
import javax.naming.spi.NamingManager;

/**
 * Created by tzoloc on 6/15/16.
 */
public class SimpleNamingContextBuilder implements InitialContextFactoryBuilder {

  /** An instance of this class bound to JNDI */
  private static volatile SimpleNamingContextBuilder activated;

  private static boolean initialized = false;

  private static final Object initializationLock = new Object();


  /**
   * Checks if a SimpleNamingContextBuilder is active.
   * @return the current SimpleNamingContextBuilder instance,
   * or {@code null} if none
   */
  public static SimpleNamingContextBuilder getCurrentContextBuilder() {
    return activated;
  }

  /**
   * If no SimpleNamingContextBuilder is already configuring JNDI,
   * create and activate one. Otherwise take the existing activate
   * SimpleNamingContextBuilder, clear it and return it.
   * <p>This is mainly intended for test suites that want to
   * reinitialize JNDI bindings from scratch repeatedly.
   * @return an empty SimpleNamingContextBuilder that can be used
   * to control JNDI bindings
   */
  public static SimpleNamingContextBuilder emptyActivatedContextBuilder() throws NamingException {
    if (activated != null) {
      // Clear already activated context builder.
      activated.clear();
    }
    else {
      // Create and activate new context builder.
      SimpleNamingContextBuilder builder = new SimpleNamingContextBuilder();
      // The activate() call will cause an assignment to the activated variable.
      builder.activate();
    }
    return activated;
  }


  private final Log logger = LogFactory.getLog(getClass());

  private final Hashtable<String,Object> boundObjects = new Hashtable<String,Object>();


  /**
   * Register the context builder by registering it with the JNDI NamingManager.
   * Note that once this has been done, {@code new InitialContext()} will always
   * return a context from this factory. Use the {@code emptyActivatedContextBuilder()}
   * static method to get an empty context (for example, in test methods).
   * @throws IllegalStateException if there's already a naming context builder
   * registered with the JNDI NamingManager
   */
  public void activate() throws IllegalStateException, NamingException {
    logger.info("Activating simple JNDI environment");
    synchronized (initializationLock) {
      if (!initialized) {
        if (NamingManager.hasInitialContextFactoryBuilder()) {
          throw new IllegalStateException(
              "Cannot activate SimpleNamingContextBuilder: there is already a JNDI provider registered. " +
                  "Note that JNDI is a JVM-wide service, shared at the JVM system class loader level, " +
                  "with no reset option. As a consequence, a JNDI provider must only be registered once per JVM.");
        }
        NamingManager.setInitialContextFactoryBuilder(this);
        initialized = true;
      }
    }
    activated = this;
  }

  /**
   * Temporarily deactivate this context builder. It will remain registered with
   * the JNDI NamingManager but will delegate to the standard JNDI InitialContextFactory
   * (if configured) instead of exposing its own bound objects.
   * <p>Call {@code activate()} again in order to expose this context builder's own
   * bound objects again. Such activate/deactivate sequences can be applied any number
   * of times (e.g. within a larger integration test suite running in the same VM).
   * @see #activate()
   */
  public void deactivate() {
    logger.info("Deactivating simple JNDI environment");
    activated = null;
  }

  /**
   * Clear all bindings in this context builder, while keeping it active.
   */
  public void clear() {
    this.boundObjects.clear();
  }

  /**
   * Bind the given object under the given name, for all naming contexts
   * that this context builder will generate.
   * @param name the JNDI name of the object (e.g. "java:comp/env/jdbc/myds")
   * @param obj the object to bind (e.g. a DataSource implementation)
   */
  public void bind(String name, Object obj) {
    if (logger.isInfoEnabled()) {
      logger.info("Static JNDI binding: [" + name + "] = [" + obj + "]");
    }
    this.boundObjects.put(name, obj);
  }


  /**
   * Simple InitialContextFactoryBuilder implementation,
   * creating a new SimpleNamingContext instance.
   * @see SimpleNamingContext
   */
  @Override
  public InitialContextFactory createInitialContextFactory(Hashtable<?,?> environment) {
    if (activated == null && environment != null) {
      Object icf = environment.get(Context.INITIAL_CONTEXT_FACTORY);
      if (icf != null) {
        Class<?> icfClass;
        if (icf instanceof Class) {
          icfClass = (Class<?>) icf;
        }
        else if (icf instanceof String) {
          icfClass = ClassUtils.resolveClassName((String) icf, getClass().getClassLoader());
        }
        else {
          throw new IllegalArgumentException("Invalid value type for environment key [" +
              Context.INITIAL_CONTEXT_FACTORY + "]: " + icf.getClass().getName());
        }
        if (!InitialContextFactory.class.isAssignableFrom(icfClass)) {
          throw new IllegalArgumentException(
              "Specified class does not implement [" + InitialContextFactory.class.getName() + "]: " + icf);
        }
        try {
          return (InitialContextFactory) icfClass.newInstance();
        }
        catch (Throwable ex) {
          throw new IllegalStateException("Cannot instantiate specified InitialContextFactory: " + icf, ex);
        }
      }
    }

    // Default case...
    return new InitialContextFactory() {
      @Override
      @SuppressWarnings("unchecked")
      public Context getInitialContext(Hashtable<?,?> environment) {
        return new SimpleNamingContext("", boundObjects, (Hashtable<String, Object>) environment);
      }
    };
  }
}
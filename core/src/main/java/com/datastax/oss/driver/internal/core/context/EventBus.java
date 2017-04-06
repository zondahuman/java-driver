/*
 * Copyright (C) 2017-2017 DataStax Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.context;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Barebones event bus implementation, that allows components to communicate without knowing about
 * each other.
 *
 * <p>This is intended for administrative events (topology changes, new connections, etc.), which
 * are comparatively rare in the driver. Do not use it for anything on the request path, because it
 * relies on synchronization.
 *
 * <p>We don't use Guava's implementation because Guava is shaded in the driver, and the event bus
 * needs to be accessible from low-level 3rd party customizations.
 */
public class EventBus {
  private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

  private final SetMultimap<Class<?>, Consumer<?>> listeners =
      Multimaps.synchronizedSetMultimap(HashMultimap.create());

  /**
   * Registers a listener for an event type.
   *
   * @return a key that is needed to unregister later.
   */
  public <T> Object register(Class<T> eventClass, Consumer<T> listener) {
    LOG.debug("Registering {} for {}", listener, eventClass);
    listeners.put(eventClass, listener);
    // The reason for the key mechanism is that this will often be used with method references,
    // and you get a different object every time you reference a method, so register(Foo::bar)
    // followed by unregister(Foo::bar) wouldn't work as expected.
    return listener;
  }

  /**
   * Unregisters a listener.
   *
   * @param key the key that was returned by {@link #register(Class, Consumer)}
   */
  public <T> boolean unregister(Object key, Class<T> eventClass) {
    LOG.debug("Unregistering {} for {}", key, eventClass);
    return listeners.remove(eventClass, key);
  }

  /**
   * Sends an event that will notify any registered listener for that class.
   *
   * <p>Listeners are looked up by an <b>exact match</b> on the class of the object, as returned by
   * {@code event.getClass()}. Listeners of a supertype won't be notified.
   *
   * <p>The listeners are invoked on the calling thread. It's their responsibility to schedule event
   * processing asynchronously if needed.
   */
  public void fire(Object event) {
    // if the exact match thing gets too cumbersome, we can reconsider, but I'd like to avoid
    // scanning all the keys with instanceof checks.
    Class<?> eventClass = event.getClass();
    for (Consumer<?> l : listeners.get(eventClass)) {
      @SuppressWarnings("unchecked")
      Consumer<Object> listener = (Consumer<Object>) l;
      LOG.trace("Notifying {} of {}", listener, event);
      listener.accept(event);
    }
  }
}

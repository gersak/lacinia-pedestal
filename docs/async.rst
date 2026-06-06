Async
=====

By default, Lacinia-Pedestal blocks the Pedestal request thread
while executing the query. The default interceptor stack includes a synchronous
query execution handler, ``query-executor-handler``, available in both
``com.walmartlabs.lacinia.pedestal3`` and ``com.walmartlabs.lacinia.pedestal2``.

Lacinia also provides an asynchronous query execution handler:
``com.walmartlabs.lacinia.pedestal2/async-query-executor-handler``.

.. note::

  ``async-query-executor-handler`` is only available in ``pedestal2``. It is not
  included in ``pedestal3``.

When used in the interceptor stack, execution starts on a Pedestal request
processing thread, but (at the discretion of individual field resolver
functions) may continue on other threads.

Further, the return value from the asynchronous handler is a channel, forcing Pedestal to
switch to async mode.

Lacinia-Pedestal does not impose any restrictions on the number of requests it will
attempt to process concurrently; normally, this is gated by the number of Pedestal
request processing threads available.

When using the asynchronous query execution handler, you should provide application-specific
interceptors to rate limit requests, or risk saturating your server.

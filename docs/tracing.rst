Request Tracing
===============

Lacinia includes `support for request tracing <https://lacinia.readthedocs.io/en/latest/tracing.html>`_, which identifies
how much time Lacinia spends parsing, validating, and processing the overall request.

By default, this is enabled by passing the header value ``lacinia-tracing`` set to ``true`` (or any non-blank string).

Further, the default is for the GraphiQL IDE to provide this value; queries using the IDE will trigger tracing behavior
(often resulting in very, very large responses).

You will typically want to disable tracing in production, by removing the enable-tracing interceptor from
the interceptor chain:

* ``pedestal3``: ``:com.walmartlabs.lacinia.pedestal3/enable-tracing``
* ``pedestal2``: ``:com.walmartlabs.lacinia.pedestal2/enable-tracing``

See the :doc:`interceptors` documentation for how to modify the default interceptor pipeline.

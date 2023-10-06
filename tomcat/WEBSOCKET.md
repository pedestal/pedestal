# Adding WebSocket support to Tomcat 10

Not much out of the basic API.

org.apache.tomcat.websocket.server.WsFilter looks promising.

jakarta.websocket.server.ServerContainer extends jakarta.websocket.WebSocketContainer

io.pedestal.websocket/add-endpoints expects jakarta.websocket.server.ServerContainer, ...

jakarta.websocket.server.ServerContainer.addEndpoint(jakarta.websocket.server.ServerEndpointConfig) is invoked by io.pedestal.websocket/add-endpoints

org.apache.tomcat.websocket.server.WsServerContainer implements ServerContainer

org.apache.tomcat.websocket.server.WsSci.init(ServletContext, boolean),
returns org.apache.tomcat.websocket.server.WsServerContainer
which extends:
- jakarta.websocket.server.ServerContainer  [there it is!]
- org.apache.tomcat.websocket.WsWebSocketContainer
- jakarta.websocket.WebSocketContainer

`init` isn't public



```text
public enum LifecycleState {
    NEW(false, null),
    INITIALIZING(false, Lifecycle.BEFORE_INIT_EVENT),
    INITIALIZED(false, Lifecycle.AFTER_INIT_EVENT),
    STARTING_PREP(false, Lifecycle.BEFORE_START_EVENT),
    STARTING(true, Lifecycle.START_EVENT),
    STARTED(true, Lifecycle.AFTER_START_EVENT),
    STOPPING_PREP(true, Lifecycle.BEFORE_STOP_EVENT),
    STOPPING(false, Lifecycle.STOP_EVENT),
    STOPPED(false, Lifecycle.AFTER_STOP_EVENT),
    DESTROYING(false, Lifecycle.BEFORE_DESTROY_EVENT),
    DESTROYED(false, Lifecycle.AFTER_DESTROY_EVENT),
    FAILED(false, null);

```

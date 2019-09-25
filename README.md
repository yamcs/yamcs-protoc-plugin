# yamcs-protoc-plugin

This is a plugin for protoc that generates java interfaces based on service definitions in proto files. The generated
interfaces extend `org.yamcs.api.Api` and can be registered against `org.yamcs.http.HttpServer`.


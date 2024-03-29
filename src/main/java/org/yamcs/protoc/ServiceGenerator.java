package org.yamcs.protoc;

import java.beans.Introspector;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.File;

public class ServiceGenerator {

    private static Map<String, DescriptorProto> messageTypes = new HashMap<>();
    private static Map<DescriptorProto, FileDescriptorProto> fileForMessage = new HashMap<>();
    private static Map<String, String> javaPackages = new HashMap<>();

    private static Map<ServiceDescriptorProto, String> serviceComments = new HashMap<>();
    private static Map<MethodDescriptorProto, String> methodComments = new HashMap<>();

    private static void scanComments(FileDescriptorProto file) {
        var services = file.getServiceList();

        for (var location : file.getSourceCodeInfo().getLocationList()) {
            if (location.hasLeadingComments()) {
                if (location.getPath(0) == FileDescriptorProto.SERVICE_FIELD_NUMBER) {
                    var service = services.get(location.getPath(1));
                    if (location.getPathCount() == 2) {
                        serviceComments.put(service, location.getLeadingComments());
                    } else if (location.getPathCount() == 4) {
                        if (location.getPath(2) == ServiceDescriptorProto.METHOD_FIELD_NUMBER) {
                            var method = service.getMethod(location.getPath(3));
                            methodComments.put(method, location.getLeadingComments());
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        var request = CodeGeneratorRequest.parseFrom(System.in);
        var responseb = CodeGeneratorResponse.newBuilder();

        // Index all messages by fully-qualified protobuf name
        for (var file : request.getProtoFileList()) {
            scanComments(file);

            var javaPackage = file.getOptions().getJavaPackage();
            javaPackages.put(file.getName(), javaPackage);

            for (var messageType : file.getMessageTypeList()) {
                var qname = file.getPackage() + "." + messageType.getName();
                messageTypes.put(qname, messageType);
                fileForMessage.put(messageType, file);
            }
        }

        for (var file : request.getProtoFileList()) {
            for (int i = 0; i < file.getServiceCount(); i++) {
                responseb.addFile(generateService(file, i));
                responseb.addFile(generateServiceClient(file, i));
            }
        }

        responseb.build().writeTo(System.out);
    }

    private static File.Builder generateService(FileDescriptorProto file, int serviceIndex) {
        var service = file.getService(serviceIndex);
        var javaPackage = file.getOptions().getJavaPackage();
        var javaName = "Abstract" + service.getName();

        var jsource = new SourceBuilder(javaName + "<T>");
        jsource.setAbstract(true);
        jsource.setJavadoc(serviceComments.get(service));
        jsource.setPackage(javaPackage);
        jsource.setImplements("Api<T>");
        var className = ServiceGenerator.class.getName();
        jsource.addAnnotation("@javax.annotation.processing.Generated(value = \"" + className + "\", date = \""
                + Instant.now() + "\")");
        jsource.addAnnotation("@SuppressWarnings(\"unchecked\")");
        jsource.addImport("com.google.protobuf.Message");
        jsource.addImport("com.google.protobuf.Descriptors.MethodDescriptor");
        jsource.addImport("com.google.protobuf.Descriptors.ServiceDescriptor");
        jsource.addImport("org.yamcs.api.Api");
        jsource.addImport("org.yamcs.api.Observer");

        for (var method : service.getMethodList()) {
            var javaMethodName = Introspector.decapitalize(method.getName());
            var inputType = messageTypes.get(method.getInputType().substring(1));
            var outputType = messageTypes.get(method.getOutputType().substring(1));

            var inputTypeJavaPackage = getJavaPackage(inputType);
            if (!inputTypeJavaPackage.equals(javaPackage)) {
                jsource.addImport(getJavaClassname(inputType));
            }

            var outputTypeJavaPackage = getJavaPackage(outputType);
            if (!outputTypeJavaPackage.equals(javaPackage)) {
                jsource.addImport(getJavaClassname(outputType));
            }

            var msource = jsource.addMethod(javaMethodName);
            msource.setJavadoc(methodComments.get(method));
            msource.setAbstract(true);
            if (method.getClientStreaming()) {
                msource.setReturn("Observer<" + inputType.getName() + ">");
                msource.addArg("T", "ctx");
                msource.addArg("Observer<" + outputType.getName() + ">", "observer");
            } else {
                msource.addArg("T", "ctx");
                msource.addArg(inputType.getName(), "request");
                msource.addArg("Observer<" + outputType.getName() + ">", "observer");
            }
        }

        // Implement "ServiceDescriptor getDescriptorForType();"
        var msource = jsource.addMethod("getDescriptorForType");
        msource.setReturn("ServiceDescriptor");
        msource.addAnnotation("@Override");
        msource.setFinal(true);
        msource.body().append("return ").append(getOuterClassname(file))
                .append(".getDescriptor().getServices().get(").append(serviceIndex).append(");\n");

        // Implement "Message getRequestPrototype(MethodDescriptor method);"
        msource = jsource.addMethod("getRequestPrototype");
        msource.setReturn("Message");
        msource.addAnnotation("@Override");
        msource.setFinal(true);
        msource.addArg("MethodDescriptor", "method");
        msource.body().append("if (method.getService() != getDescriptorForType()) {\n");
        msource.body().append("    throw new IllegalArgumentException(\"Method not contained by this service.\");\n");
        msource.body().append("}\n");
        msource.body().append("switch (method.getIndex()) {\n");
        for (int i = 0; i < service.getMethodCount(); i++) {
            var method = service.getMethod(i);
            var inputType = messageTypes.get(method.getInputType().substring(1));
            msource.body().append("case ").append(i).append(":\n");
            msource.body().append("    return ").append(inputType.getName()).append(".getDefaultInstance();\n");
        }
        msource.body().append("default:\n");
        msource.body().append("    throw new IllegalStateException();\n");
        msource.body().append("}\n");

        // Implement "Message getResponsePrototype(MethodDescriptor method);"
        msource = jsource.addMethod("getResponsePrototype");
        msource.setReturn("Message");
        msource.setFinal(true);
        msource.addAnnotation("@Override");
        msource.addArg("MethodDescriptor", "method");
        msource.body().append("if (method.getService() != getDescriptorForType()) {\n");
        msource.body().append("    throw new IllegalArgumentException(\"Method not contained by this service.\");\n");
        msource.body().append("}\n");
        msource.body().append("switch (method.getIndex()) {\n");
        for (int i = 0; i < service.getMethodCount(); i++) {
            var method = service.getMethod(i);
            var outputType = messageTypes.get(method.getOutputType().substring(1));
            msource.body().append("case ").append(i).append(":\n");
            msource.body().append("    return ").append(outputType.getName()).append(".getDefaultInstance();\n");
        }
        msource.body().append("default:\n");
        msource.body().append("    throw new IllegalStateException();\n");
        msource.body().append("}\n");

        // Implement "void callMethod(MethodDescriptor method, Message request, Observer<Message> observer)"
        msource = jsource.addMethod("callMethod");
        msource.setFinal(true);
        msource.addAnnotation("@Override");
        msource.addArg("MethodDescriptor", "method");
        msource.addArg("T", "ctx");
        msource.addArg("Message", "request");
        msource.addArg("Observer<Message>", "future");
        msource.body().append("if (method.getService() != getDescriptorForType()) {\n");
        msource.body().append("    throw new IllegalArgumentException(\"Method not contained by this service.\");\n");
        msource.body().append("}\n");
        msource.body().append("switch (method.getIndex()) {\n");
        for (int i = 0; i < service.getMethodCount(); i++) {
            var method = service.getMethod(i);
            if (!method.getClientStreaming()) {
                var javaMethodName = Introspector.decapitalize(method.getName());
                var inputType = messageTypes.get(method.getInputType().substring(1));
                var outputType = messageTypes.get(method.getOutputType().substring(1));
                var callArgs = "ctx, (" + inputType.getName() + ") request";
                callArgs += ", (Observer<" + outputType.getName() + ">)(Object) future";
                msource.body().append("case ").append(i).append(":\n");
                msource.body().append("    ").append(javaMethodName).append("(").append(callArgs).append(");\n");
                msource.body().append("    return;\n");
            }
        }
        msource.body().append("default:\n");
        msource.body().append("    throw new IllegalStateException();\n");
        msource.body().append("}\n");

        // Implement "Observer<Message> callMethod(MethodDescriptor method, Observer<Message> observer)"
        msource = jsource.addMethod("callMethod");
        msource.setFinal(true);
        msource.addAnnotation("@Override");
        msource.setReturn("Observer<Message>");
        msource.addArg("MethodDescriptor", "method");
        msource.addArg("T", "ctx");
        msource.addArg("Observer<Message>", "future");
        msource.body().append("if (method.getService() != getDescriptorForType()) {\n");
        msource.body().append("    throw new IllegalArgumentException(\"Method not contained by this service.\");\n");
        msource.body().append("}\n");
        msource.body().append("switch (method.getIndex()) {\n");
        for (int i = 0; i < service.getMethodCount(); i++) {
            var method = service.getMethod(i);
            if (method.getClientStreaming()) {
                var javaMethodName = Introspector.decapitalize(method.getName());
                var outputType = messageTypes.get(method.getOutputType().substring(1));
                var callArgs = "ctx, (Observer<" + outputType.getName() + ">)(Object) future";
                msource.body().append("case ").append(i).append(":\n");
                msource.body().append("    return (Observer<Message>)(Object) ").append(javaMethodName).append("(")
                        .append(callArgs).append(");\n");
            }
        }
        msource.body().append("default:\n");
        msource.body().append("    throw new IllegalStateException();\n");
        msource.body().append("}\n");

        var filename = javaPackage.replace('.', '/') + "/" + javaName + ".java";
        return File.newBuilder().setName(filename).setContent(jsource.toString());
    }

    private static File.Builder generateServiceClient(FileDescriptorProto file, int serviceIndex) {
        var service = file.getService(serviceIndex);
        var javaPackage = file.getOptions().getJavaPackage();
        var javaName = service.getName() + "Client";

        var jsource = new SourceBuilder(javaName);
        jsource.setJavadoc(serviceComments.get(service));
        jsource.setPackage(javaPackage);
        jsource.setExtends("Abstract" + service.getName() + "<Void>");
        jsource.addImport("org.yamcs.api.MethodHandler");
        jsource.addImport("org.yamcs.api.Observer");
        var className = ServiceGenerator.class.getName();
        jsource.addAnnotation("@javax.annotation.processing.Generated(value = \"" + className + "\", date = \""
                + Instant.now() + "\")");

        jsource.addField("MethodHandler", "handler");

        var csource = jsource.addConstructor();
        csource.addArg("MethodHandler", "handler");
        csource.body().append("this.handler = handler;");

        for (int i = 0; i < service.getMethodCount(); i++) {
            var method = service.getMethod(i);
            var javaMethodName = Introspector.decapitalize(method.getName());
            var inputType = messageTypes.get(method.getInputType().substring(1));
            var outputType = messageTypes.get(method.getOutputType().substring(1));

            var inputTypeJavaPackage = getJavaPackage(inputType);
            if (!inputTypeJavaPackage.equals(javaPackage)) {
                jsource.addImport(getJavaClassname(inputType));
            }

            var outputTypeJavaPackage = getJavaPackage(outputType);
            if (!outputTypeJavaPackage.equals(javaPackage)) {
                jsource.addImport(getJavaClassname(outputType));
            }

            var msource = jsource.addMethod(javaMethodName);
            msource.setJavadoc(methodComments.get(method));
            msource.addAnnotation("@Override");
            msource.setFinal(true);

            if (method.getClientStreaming()) {
                msource.addAnnotation("@SuppressWarnings(\"unchecked\")");
                msource.setReturn("Observer<" + inputType.getName() + ">");
                msource.addArg("Void", "ctx");
                msource.addArg("Observer<" + outputType.getName() + ">", "observer");
                msource.body()
                        .append("return (Observer<" + inputType.getName() + ">)(Object) handler.streamingCall(\n");
                msource.body().append("    getDescriptorForType().getMethods().get(").append(i).append("),\n");
                msource.body().append("    ").append(inputType.getName()).append(".getDefaultInstance(),\n");
                msource.body().append("    ").append(outputType.getName()).append(".getDefaultInstance(),\n");
                msource.body().append("    observer);");
            } else {
                msource.addArg("Void", "ctx");
                msource.addArg(inputType.getName(), "request");
                msource.addArg("Observer<" + outputType.getName() + ">", "observer");

                msource.body().append("handler.call(\n");
                msource.body().append("    getDescriptorForType().getMethods().get(").append(i).append("),\n");
                msource.body().append("    request,\n");
                msource.body().append("    ").append(outputType.getName()).append(".getDefaultInstance(),\n");
                msource.body().append("    observer);");
            }
        }

        var filename = javaPackage.replace('.', '/') + "/" + javaName + ".java";
        return File.newBuilder().setName(filename).setContent(jsource.toString());
    }

    private static String getJavaPackage(DescriptorProto messageType) {
        var file = fileForMessage.get(messageType);
        if (file.getOptions().getJavaMultipleFiles()) {
            return file.getOptions().getJavaPackage();
        } else {
            var outerClassname = getOuterClassname(file);
            return file.getOptions().getJavaPackage() + "." + outerClassname;
        }
    }

    private static String getOuterClassname(FileDescriptorProto file) {
        if (file.getOptions().hasJavaOuterClassname()) {
            return file.getOptions().getJavaOuterClassname();
        } else {
            var name = new java.io.File(file.getName()).toPath().getFileName().toString().replace(".proto", "");
            return name.substring(0, 1).toUpperCase() + name.substring(1);
        }
    }

    private static String getJavaClassname(DescriptorProto messageType) {
        return getJavaPackage(messageType) + "." + messageType.getName();
    }
}

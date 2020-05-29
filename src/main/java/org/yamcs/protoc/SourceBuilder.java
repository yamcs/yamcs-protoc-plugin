package org.yamcs.protoc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SourceBuilder {

    private String package_;
    private Set<String> imports = new HashSet<>();
    private List<String> annotations = new ArrayList<>();
    private boolean abstract_;
    private String javadoc;
    private String class_;
    private String extends_;
    private String implements_;
    private List<String> fieldTypes = new ArrayList<>();
    private List<String> fieldNames = new ArrayList<>();
    private List<ConstructorBuilder> constructors = new ArrayList<>();
    private List<MethodBuilder> methods = new ArrayList<>();

    public SourceBuilder(String class_) {
        this.class_ = class_;
    }

    /**
     * Sets Javadoc. But unlike Javadoc, this does not expect HTML input and so the input will be surrounded with
     * &lt;pre&gt;&lt;/pre&gt; tags and escaped as necessary.
     */
    public void setJavadoc(String javadoc) {
        this.javadoc = javadoc;
    }

    public void addAnnotation(String annotation) {
        annotations.add(annotation);
    }

    public void setPackage(String package_) {
        this.package_ = package_;
    }

    public void setAbstract(boolean abstract_) {
        this.abstract_ = abstract_;
    }

    public void setExtends(String extends_) {
        this.extends_ = extends_;
    }

    public void setImplements(String implements_) {
        this.implements_ = implements_;
    }

    public void addImport(String import_) {
        imports.add(import_);
    }

    public void addField(String type, String name) {
        fieldTypes.add(type);
        fieldNames.add(name);
    }

    public ConstructorBuilder addConstructor() {
        ConstructorBuilder constructor = new ConstructorBuilder();
        constructors.add(constructor);
        return constructor;
    }

    public MethodBuilder addMethod(String name) {
        MethodBuilder method = new MethodBuilder(name);
        methods.add(method);
        return method;
    }

    public static class ConstructorBuilder {

        private List<String> argTypes = new ArrayList<>();
        private List<String> argNames = new ArrayList<>();
        private StringBuilder body = new StringBuilder();

        public void addArg(String type, String name) {
            argTypes.add(type);
            argNames.add(name);
        }

        public StringBuilder body() {
            return body;
        }
    }

    public static class MethodBuilder {

        private String return_ = "void";
        private String name;
        private boolean abstract_;
        private boolean final_;
        private String javadoc;
        private List<String> argTypes = new ArrayList<>();
        private List<String> argNames = new ArrayList<>();
        private List<String> annotations = new ArrayList<>();
        private StringBuilder body = new StringBuilder();

        public MethodBuilder(String name) {
            this.name = name;
        }

        public void setReturn(String return_) {
            this.return_ = return_;
        }

        public void setJavadoc(String javadoc) {
            this.javadoc = javadoc;
        }

        public void setAbstract(boolean abstract_) {
            this.abstract_ = abstract_;
        }

        public void setFinal(boolean final_) {
            this.final_ = final_;
        }

        public void addArg(String type, String name) {
            argTypes.add(type);
            argNames.add(name);
        }

        public void addAnnotation(String annotation) {
            annotations.add(annotation);
        }

        public StringBuilder body() {
            return body;
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("package ").append(package_).append(";\n\n");

        List<String> sortedImports = new ArrayList<>(imports);
        Collections.sort(sortedImports);
        for (String import_ : sortedImports) {
            if (!import_.equals(package_)) {
                buf.append("import ").append(import_).append(";\n");
            }
        }
        buf.append("\n");

        if (javadoc != null) {
            buf.append("/**\n");
            buf.append(generateJavadocBody(javadoc, " * "));
            buf.append(" */\n");
        }

        for (String annotation : annotations) {
            buf.append(annotation).append("\n");
        }

        String modifiers = "public";
        if (abstract_) {
            modifiers += " abstract";
        }

        buf.append(modifiers).append(" class ").append(class_);
        if (extends_ != null) {
            buf.append(" extends ").append(extends_);
        }
        if (implements_ != null) {
            buf.append(" implements ").append(implements_);
        }
        buf.append(" {\n");

        for (int i = 0; i < fieldTypes.size(); i++) {
            buf.append("\n    private final ").append(fieldTypes.get(i)).append(" ").append(fieldNames.get(i))
                    .append(";");
        }
        if (!fieldTypes.isEmpty()) {
            buf.append("\n");
        }

        for (ConstructorBuilder constructor : constructors) {
            buf.append("\n");
            modifiers = "public";
            buf.append("    ").append(modifiers).append(" ").append(class_);
            buf.append("(");
            for (int i = 0; i < constructor.argTypes.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append(constructor.argTypes.get(i)).append(" ").append(constructor.argNames.get(i));
            }
            buf.append(") {\n");
            String[] lines = constructor.body.toString().trim().split("\n");
            for (int i = 0; i < lines.length; i++) {
                buf.append("        ").append(lines[i]).append("\n");
            }
            buf.append("    }\n");
        }

        for (MethodBuilder method : methods) {
            buf.append("\n");
            if (method.javadoc != null) {
                buf.append("    /**\n");
                buf.append(generateJavadocBody(method.javadoc, "     * "));
                buf.append("     */\n");
            }
            for (String annotation : method.annotations) {
                buf.append("    ").append(annotation).append("\n");
            }
            modifiers = "public";
            if (method.abstract_) {
                modifiers += " abstract";
            }
            if (method.final_) {
                modifiers += " final";
            }
            if (method.abstract_) {
                buf.append("    ").append(modifiers).append(" ").append(method.return_).append(" ").append(method.name);
                buf.append("(");
                for (int i = 0; i < method.argTypes.size(); i++) {
                    if (i > 0) {
                        buf.append(", ");
                    }
                    buf.append(method.argTypes.get(i)).append(" ").append(method.argNames.get(i));
                }
                buf.append(");\n");
            } else {
                buf.append("    ").append(modifiers).append(" ").append(method.return_).append(" ").append(method.name);
                buf.append("(");
                for (int i = 0; i < method.argTypes.size(); i++) {
                    if (i > 0) {
                        buf.append(", ");
                    }
                    buf.append(method.argTypes.get(i)).append(" ").append(method.argNames.get(i));
                }
                buf.append(") {\n");
                String[] lines = method.body.toString().trim().split("\n");
                for (int i = 0; i < lines.length; i++) {
                    buf.append("        ").append(lines[i]).append("\n");
                }
                buf.append("    }\n");
            }
        }

        return buf.append("}\n").toString();
    }

    private static String generateJavadocBody(String raw, String prefix) {
        String escaped = "<pre>\n" + raw.replace("@", "{@literal @}")
                .replace("/*", "{@literal /}*")
                .replace("*/", "*{@literal /}")
                .replace("<", "&lt;")
                .replace(">", "&gt;") + "</pre>";
        StringBuilder body = new StringBuilder();
        for (String line : escaped.split("\n")) {
            body.append(prefix).append(line).append("\n");
        }
        return body.toString();
    }
}

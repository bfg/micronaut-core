/*
 * Copyright 2017 original authors
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
package org.particleframework.inject.annotation;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.particleframework.core.annotation.AnnotationMetadata;
import org.particleframework.core.reflect.ReflectionUtils;
import org.particleframework.core.util.CollectionUtils;
import org.particleframework.inject.writer.AbstractClassFileWriter;
import org.particleframework.inject.writer.ClassGenerationException;

import java.io.File;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Responsible for writing class files that are instances of {@link AnnotationMetadata}
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class AnnotationMetadataWriter extends AbstractClassFileWriter {

    private static final org.objectweb.asm.commons.Method METHOD_MAP_OF = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalMethod(
                    CollectionUtils.class,
                    "mapOf",
                    Object[].class
            )
    );

    private static final org.objectweb.asm.commons.Method METHOD_EMPTY_MAP = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalMethod(
                    Collections.class,
                    "emptyMap"
            )
    );
    private static final org.objectweb.asm.commons.Method METHOD_SET_OF = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalMethod(
                    CollectionUtils.class,
                    "setOf",
                    Object[].class
            )
    );
    private static final org.objectweb.asm.commons.Method CONSTRUCTOR_ANNOTATION_METADATA = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalConstructor(
                    DefaultAnnotationMetadata.class,
                    Set.class,
                    Set.class,
                    Map.class,
                    Map.class,
                    Map.class
            )
    );
    static final String CLASS_NAME_SUFFIX = "$$AnnotationMetadata";

    private final String className;
    private final DefaultAnnotationMetadata annotationMetadata;

    /**
     * Constructs a new writer for the given class name and metadata
     *
     * @param className The class name for which the metadata relates
     * @param annotationMetadata The annotation metadata
     */
    public AnnotationMetadataWriter(String className, AnnotationMetadata annotationMetadata) {
        this.className = className + CLASS_NAME_SUFFIX;
        if (annotationMetadata instanceof DefaultAnnotationMetadata) {
            this.annotationMetadata = (DefaultAnnotationMetadata) annotationMetadata;
        } else {
            throw new ClassGenerationException("Compile time metadata required to generate class: " + className);
        }
    }

    /**
     * Write the class to the target directory
     *
     * @param targetDir The target directory
     */
    public void writeTo(File targetDir) {
        try {
            ClassWriter classWriter = generateClassBytes();

            writeClassToDisk(targetDir, classWriter, getInternalName(className));

        } catch (Throwable e) {
            throw new ClassGenerationException("Error generating annotation metadata: " + e.getMessage(), e);
        }
    }

    /**
     * Write the class to the output stream, such a JavaFileObject created from a java annotation processor Filer object
     *
     * @param outputStream the output stream pointing to the target class file
     */
    public void writeTo(OutputStream outputStream) {
        try {
            ClassWriter classWriter = generateClassBytes();

            writeClassToDisk(outputStream, classWriter);
        } catch (Throwable e) {
            throw new ClassGenerationException("Error generating annotation metadata: " + e.getMessage(), e);
        }
    }

    private ClassWriter generateClassBytes() {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        Type superType = Type.getType(DefaultAnnotationMetadata.class);
        startClass(classWriter, getInternalName(className), superType);

        GeneratorAdapter constructor = startConstructor(classWriter);

        constructor.loadThis();
        // 1st argument: the declared annotations
        pushCreateSetCall(constructor, annotationMetadata.declaredAnnotations);
        // 2nd argument: the declared stereotypes
        pushCreateSetCall(constructor, annotationMetadata.declaredStereotypes);
        // 3rd argument: all stereotypes
        pushCreateAnnotationData(constructor, annotationMetadata.allStereotypes);
        // 4th argument: all annotations
        pushCreateAnnotationData(constructor, annotationMetadata.allAnnotations);
        // 5th argument: annotations by stereotype
        pushCreateAnnotationsByStereotypeData(constructor, annotationMetadata.annotationsByStereotype);

        constructor.invokeConstructor(superType, CONSTRUCTOR_ANNOTATION_METADATA);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        return classWriter;
    }

    private static void pushCreateSetCall(GeneratorAdapter methodVisitor, Set<String> names) {
        int totalSize = names == null ? 0 : names.size();
        if (totalSize > 0) {

            // start a new array
            pushNewArray(methodVisitor, Object.class, totalSize);
            int i = 0;
            for (String name : names) {
                // use the property name as the key
                pushStoreStringInArray(methodVisitor, i++, totalSize, name);
                // use the property type as the value
            }
            // invoke the AbstractBeanDefinition.createMap method
            methodVisitor.invokeStatic(Type.getType(CollectionUtils.class), METHOD_SET_OF);
        } else {
            methodVisitor.visitInsn(ACONST_NULL);
        }
    }

    private static void pushCreateAnnotationsByStereotypeData(GeneratorAdapter methodVisitor, Map<String, Set<String>> annotationData) {
        int totalSize = annotationData == null ? 0 : annotationData.size() * 2;
        if (totalSize > 0) {

            // start a new array
            pushNewArray(methodVisitor, Object.class, totalSize);
            int i = 0;
            for (Map.Entry<String, Set<String>> entry : annotationData.entrySet()) {
                // use the property name as the key
                String annotationName = entry.getKey();
                pushStoreStringInArray(methodVisitor, i++, totalSize, annotationName);
                // use the property type as the value
                pushStoreInArray(methodVisitor, i++, totalSize, () ->
                        pushCreateSetCall(methodVisitor, entry.getValue())
                );
            }
            // invoke the AbstractBeanDefinition.createMap method
            methodVisitor.invokeStatic(Type.getType(CollectionUtils.class), METHOD_MAP_OF);
        } else {
            methodVisitor.visitInsn(ACONST_NULL);
        }
    }

    private static void pushCreateAnnotationData(GeneratorAdapter methodVisitor, Map<String, Map<CharSequence, Object>> annotationData) {
        int totalSize = annotationData == null ? 0 : annotationData.size() * 2;
        if (totalSize > 0) {

            // start a new array
            pushNewArray(methodVisitor, Object.class, totalSize);
            int i = 0;
            for (Map.Entry<String, Map<CharSequence, Object>> entry : annotationData.entrySet()) {
                // use the property name as the key
                String annotationName = entry.getKey();
                pushStoreStringInArray(methodVisitor, i++, totalSize, annotationName);
                // use the property type as the value
                Map<CharSequence, Object> attributes = entry.getValue();
                if (attributes.isEmpty()) {
                    pushStoreInArray(methodVisitor, i++, totalSize, () ->
                            methodVisitor.invokeStatic(Type.getType(Collections.class), METHOD_EMPTY_MAP)
                    );
                } else {
                    pushStoreInArray(methodVisitor, i++, totalSize, () ->
                            pushAnnotationAttributes(methodVisitor, attributes)
                    );
                }
            }
            // invoke the AbstractBeanDefinition.createMap method
            methodVisitor.invokeStatic(Type.getType(CollectionUtils.class), METHOD_MAP_OF);
        } else {
            methodVisitor.visitInsn(ACONST_NULL);
        }
    }

    private static void pushAnnotationAttributes(GeneratorAdapter methodVisitor, Map<CharSequence, Object> annotationData) {
        int totalSize = annotationData.size() * 2;
        // start a new array
        pushNewArray(methodVisitor, Object.class, totalSize);
        int i = 0;
        for (Map.Entry<CharSequence, Object> entry : annotationData.entrySet()) {
            // use the property name as the key
            String memberName = entry.getKey().toString();
            pushStoreStringInArray(methodVisitor, i++, totalSize, memberName);
            // use the property type as the value
            Object value = entry.getValue();
            pushStoreInArray(methodVisitor, i++, totalSize, () ->
                    pushValue(methodVisitor, value)
            );
        }
        // invoke the AbstractBeanDefinition.createMap method
        methodVisitor.invokeStatic(Type.getType(CollectionUtils.class), METHOD_MAP_OF);

    }

    private static void pushValue(GeneratorAdapter methodVisitor, Object value) {
        if(value == null) {
            methodVisitor.visitInsn(ACONST_NULL);
        }
        else if(value instanceof Boolean) {
            methodVisitor.push((Boolean)value);
            pushBoxPrimitiveIfNecessary(boolean.class, methodVisitor);
        }
        else if(value instanceof String) {
            methodVisitor.push((String)value);
        }
        else if(value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            int len = array.length;
            pushNewArray(methodVisitor, Object.class, len);
            for (int i = 0; i < array.length; i++) {
                int index = i;
                pushStoreInArray(methodVisitor, i, len, () ->
                        pushValue(methodVisitor, array[index])
                );
            }
        }
        else if(value instanceof Long) {
            methodVisitor.push(((Long) value));
            pushBoxPrimitiveIfNecessary(long.class, methodVisitor);
        }
        else if(value instanceof Double) {
            methodVisitor.push(((Double) value));
            pushBoxPrimitiveIfNecessary(double.class, methodVisitor);
        }
        else if(value instanceof Float) {
            methodVisitor.push(((Float) value));
            pushBoxPrimitiveIfNecessary(float.class, methodVisitor);
        }
        else if(value instanceof Number) {
            methodVisitor.push(((Number) value).intValue());
            pushBoxPrimitiveIfNecessary(ReflectionUtils.getPrimitiveType(value.getClass()), methodVisitor);
        }
        else {
            methodVisitor.visitInsn(ACONST_NULL);
        }
    }
}

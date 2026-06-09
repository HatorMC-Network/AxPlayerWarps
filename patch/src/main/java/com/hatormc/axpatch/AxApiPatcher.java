package com.hatormc.axpatch;

import org.objectweb.asm.*;

import java.io.*;
import java.util.*;
import java.util.jar.*;

public class AxApiPatcher {

    private static final String LISTENING_FIELD = "listening";
    private static final String OUTBOUND_FIELD  = "outboundListening";
    private static final String WRITE_METHOD    = "write";
    private static final String WRITE_DESC =
            "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;" +
            "Lio/netty/channel/ChannelPromise;)V";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java -jar patch-tool.jar <input-jar> <output-jar>");
            System.exit(1);
        }

        File input  = new File(args[0]);
        File output = new File(args[1]);

        if (!input.isFile()) {
            System.err.println("Input file not found: " + args[0]);
            System.exit(1);
        }

        LinkedHashMap<String, byte[]> contents = new LinkedHashMap<>();
        String patchedEntry = null;

        try (JarFile jar = new JarFile(input)) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                byte[] bytes = readAll(jar.getInputStream(entry));

                if (entry.getName().endsWith(".class") && isTargetClass(bytes)) {
                    if (isAlreadyPatched(bytes)) {
                        System.err.println("ERROR: " + entry.getName() +
                                " already contains '" + OUTBOUND_FIELD +
                                "'. Pass the original (unpatched) JAR.");
                        System.exit(1);
                    }
                    System.out.println("Patching: " + entry.getName());
                    bytes = patch(bytes);
                    patchedEntry = entry.getName();
                }

                contents.put(entry.getName(), bytes);
            }
        }

        if (patchedEntry == null) {
            System.err.println("ERROR: Target class not found — expected a class with a" +
                    " boolean '" + LISTENING_FIELD + "' field and a " + WRITE_METHOD + "() method.");
            System.exit(1);
        }

        try (JarOutputStream jos =
                     new JarOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            for (Map.Entry<String, byte[]> e : contents.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }

        System.out.println("Patched JAR written to: " + output.getAbsolutePath());
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (InputStream stream = is) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = stream.read(tmp)) != -1) buf.write(tmp, 0, n);
            return buf.toByteArray();
        }
    }

    /** True if the class has a boolean {@code listening} field AND the target {@code write()} method. */
    private static boolean isTargetClass(byte[] bytes) {
        boolean[] flags = {false, false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc,
                                           String sig, Object val) {
                if (LISTENING_FIELD.equals(name) && "Z".equals(desc)) flags[0] = true;
                return null;
            }
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (WRITE_METHOD.equals(name) && WRITE_DESC.equals(desc)) flags[1] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return flags[0] && flags[1];
    }

    private static boolean isAlreadyPatched(byte[] bytes) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc,
                                           String sig, Object val) {
                if (OUTBOUND_FIELD.equals(name) && "Z".equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return found[0];
    }

    private static byte[] patch(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        // COMPUTE_FRAMES regenerates all stack map frames from scratch.
        // We override getCommonSuperClass because Netty/AxAPI classes are not on the
        // patcher's classpath; falling back to Object is safe for frame merges here.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Exception e) {
                    return "java/lang/Object";
                }
            }
        };
        reader.accept(new PatchingVisitor(writer), 0);
        return writer.toByteArray();
    }

    // -------------------------------------------------------------------------

    private static final class PatchingVisitor extends ClassVisitor {
        private String owner;
        private String superName;
        private boolean fieldInjected;

        PatchingVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public void visit(int version, int access, String name, String sig,
                          String superName, String[] ifaces) {
            this.owner     = name;
            this.superName = superName;
            super.visit(version, access, name, sig, superName, ifaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc,
                                       String sig, Object value) {
            maybeInjectField();
            return super.visitField(access, name, desc, sig, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String sig, String[] exceptions) {
            maybeInjectField();
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
            if (WRITE_METHOD.equals(name) && WRITE_DESC.equals(desc))
                return new WriteGuardInjector(mv, owner, superName);
            return mv;
        }

        @Override
        public void visitEnd() {
            maybeInjectField(); // handles classes with no fields
            super.visitEnd();
        }

        private void maybeInjectField() {
            if (fieldInjected) return;
            fieldInjected = true;
            FieldVisitor fv = super.visitField(Opcodes.ACC_PRIVATE, OUTBOUND_FIELD, "Z", null, null);
            if (fv != null) fv.visitEnd();
        }
    }

    /**
     * Prepends to write():
     *   if (!this.outboundListening) { super.write(ctx, msg, promise); return; }
     */
    private static final class WriteGuardInjector extends MethodVisitor {
        private final String owner;
        private final String superName;

        WriteGuardInjector(MethodVisitor mv, String owner, String superName) {
            super(Opcodes.ASM9, mv);
            this.owner     = owner;
            this.superName = superName;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            Label resume = new Label();

            // if (this.outboundListening) goto resume
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, owner, OUTBOUND_FIELD, "Z");
            super.visitJumpInsn(Opcodes.IFNE, resume);

            // super.write(ctx, msg, promise); return;
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitVarInsn(Opcodes.ALOAD, 2);
            super.visitVarInsn(Opcodes.ALOAD, 3);
            super.visitMethodInsn(Opcodes.INVOKESPECIAL, superName,
                    WRITE_METHOD, WRITE_DESC, false);
            super.visitInsn(Opcodes.RETURN);

            super.visitLabel(resume);
        }
    }
}

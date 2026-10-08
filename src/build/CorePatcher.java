package build;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;

public final class CorePatcher implements Opcodes {
    private static final String INPUT_SHA = "e1a69eb743ede60b213a0fe7f8b83d4fcab773036d256cc4543a336f3b058a33";
    private static final String GAME = "zombie/network/GameServer", HOOK = "pzshield/ShieldHooks";
    private static final String QUEUE = "java/util/concurrent/ConcurrentLinkedQueue";
    private static final String[] FIELDS = {"MainLoopNetDataHighPriorityQ", "MainLoopPlayerUpdateQ", "MainLoopNetDataQ"};
    private static final String QDESC = "Ljava/util/concurrent/ConcurrentLinkedQueue;";
    private static final String DATA = "zombie/network/ZomboidNetData";
    private static final String POOL = "zombie/network/ZomboidNetDataPool";
    private static final String VEHICLE = "zombie/network/packets/vehicle/VehiclePhysicsPacket";
    private static URLClassLoader loader;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("CorePatcher input.jar runtime-classes output.jar");
        Path input = Path.of(args[0]), runtime = Path.of(args[1]), output = Path.of(args[2]);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
        require(INPUT_SHA.equals(hash), "Input JAR does not match the audited artifact; refusing to patch");
        require(!input.toRealPath().equals(output.toAbsolutePath().normalize()) &&
            (!Files.exists(output) || !Files.isSameFile(input, output)), "Never overwrite input JAR");
        Path staged = Files.createTempFile(output.toAbsolutePath().getParent(), ".pzshield-build-", ".jar.tmp");
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        loader = new URLClassLoader(new java.net.URL[]{runtime.toUri().toURL(), input.toUri().toURL()}, CorePatcher.class.getClassLoader());
        try (JarFile jar = new JarFile(input.toFile())) {
            replacements.put(GAME + ".class", patchServer(read(jar, GAME + ".class")));
            replacements.put("zombie/core/raknet/UdpEngine.class", patchEngine(read(jar, "zombie/core/raknet/UdpEngine.class")));
            replacements.put(POOL + ".class", patchPool(read(jar, POOL + ".class")));
            replacements.put(VEHICLE + ".class", patchVehicle(read(jar, VEHICLE + ".class")));
            for (var entry : replacements.entrySet()) {
                StringWriter errors = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(entry.getValue()), loader, false, new PrintWriter(errors));
                require(errors.toString().isBlank(), "Bytecode verification failed: " + entry.getKey() + "\n" + errors);
            }
            try (var paths = Files.walk(runtime.resolve("pzshield"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList())
                    replacements.put(runtime.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(staged))) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    require(!entry.getName().matches("META-INF/.*\\.(SF|RSA|DSA)"), "Signed input requires a separate signing workflow");
                    ZipEntry copy = new ZipEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    out.putNextEntry(copy);
                    byte[] replacement = replacements.remove(entry.getName());
                    if (replacement != null) out.write(replacement);
                    else try (var stream = jar.getInputStream(entry)) { stream.transferTo(out); }
                    out.closeEntry();
                }
                for (var entry : replacements.entrySet()) {
                    ZipEntry added = new ZipEntry(entry.getKey()); added.setTime(0);
                    out.putNextEntry(added); out.write(entry.getValue()); out.closeEntry();
                }
            }
            verifyArchive(input, staged);
            Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { loader.close(); Files.deleteIfExists(staged); }
        System.out.println("PASS: 4 game classes patched; other entries preserved; ASM verification passed");
    }

    private static byte[] patchServer(byte[] original) {
        ClassNode node = node(original);
        MethodNode main = method(node, "main", "([Ljava/lang/String;)V");
        int collected = 0, taken = 0, cleared = 0;
        for (AbstractInsnNode insn : main.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.equals("java/util/ArrayList")) continue;
            if (call.name.equals("add") && call.desc.equals("(Ljava/lang/Object;)Z") &&
                    previousCode(previousCode(call)) instanceof FieldInsnNode field && field.name.equals("MainLoopNetData2")) {
                require(collected < 3, "Unexpected batch collection site");
                main.instructions.insertBefore(call, new FieldInsnNode(GETSTATIC, GAME, FIELDS[collected++], QDESC));
                main.instructions.set(call, new MethodInsnNode(INVOKESTATIC, HOOK, "collect",
                    "(Ljava/util/ArrayList;Ljava/lang/Object;" + QDESC + ")Z", false));
            } else if (call.name.equals("get") && call.desc.equals("(I)Ljava/lang/Object;") &&
                    previousCode(previousCode(call)) instanceof FieldInsnNode field && field.name.equals("MainLoopNetData2")) {
                main.instructions.set(call, new MethodInsnNode(INVOKESTATIC, HOOK, "takeBatch",
                    "(Ljava/util/ArrayList;I)Ljava/lang/Object;", false));
                ++taken;
            } else if (call.name.equals("clear") && previousCode(call) instanceof FieldInsnNode field && field.name.equals("MainLoopNetData2")) {
                main.instructions.set(call, new MethodInsnNode(INVOKESTATIC, HOOK, "clearBatch", "(Ljava/util/ArrayList;)V", false));
                ++cleared;
            }
        }
        require(collected == 3 && taken == 3 && cleared == 4, "Unexpected batch lifecycle layout");
        int replaced = 0;
        for (AbstractInsnNode insn : main.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/ArrayList") && call.name.equals("size") &&
                    nextCode(call) instanceof JumpInsnNode jump && jump.getOpcode() == IF_ICMPGE &&
                    previousCode(call) instanceof FieldInsnNode list && list.name.equals("MainLoopNetData2")) {
                require(previousCode(list) instanceof VarInsnNode, "Unknown queue loop index");
                VarInsnNode index = (VarInsnNode) previousCode(list);
                require(index.getOpcode() == ILOAD && replaced < 3, "Unknown queue loop layout");
                InsnList args = new InsnList();
                args.add(new VarInsnNode(ILOAD, index.var));
                args.add(new FieldInsnNode(GETSTATIC, GAME, FIELDS[replaced++], QDESC));
                main.instructions.insertBefore(call, args);
                main.instructions.set(call, new MethodInsnNode(INVOKESTATIC, HOOK, "phaseSize",
                    "(Ljava/util/ArrayList;ILjava/util/concurrent/ConcurrentLinkedQueue;)I", false));
            }
        }
        require(replaced == 3, "Expected exactly three FIFO processing loops");
        // Bypass the old destructive vehicle-drop branch only while the new scheduler is enabled.
        int vehicleChecks = 0;
        for (AbstractInsnNode insn : main.instructions.toArray()) {
            if (insn.getOpcode() == IREM && nextCode(insn) instanceof JumpInsnNode jump && jump.getOpcode() == IFNE &&
                    previousCode(insn) instanceof IntInsnNode constant && constant.operand == 10) {
                AbstractInsnNode start = previousCode(constant);
                require(start instanceof VarInsnNode && start.getOpcode() == ILOAD, "Unknown vehicle budget check");
                LabelNode originalCheck = new LabelNode();
                InsnList gate = new InsnList();
                gate.add(new FieldInsnNode(GETSTATIC, "pzshield/ShieldConfig", "ENABLED", "Z"));
                gate.add(new JumpInsnNode(IFEQ, originalCheck));
                gate.add(new FieldInsnNode(GETSTATIC, "pzshield/ShieldConfig", "QUEUES", "Z"));
                gate.add(new JumpInsnNode(IFEQ, originalCheck));
                gate.add(new JumpInsnNode(GOTO, jump.label));
                gate.add(originalCheck);
                main.instructions.insertBefore(start, gate);
                ++vehicleChecks;
            }
        }
        require(vehicleChecks == 1, "Expected exactly one destructive vehicle-drop check");
        MethodNode init = method(node, "<clinit>", "()V");
        int queues = 0;
        for (AbstractInsnNode insn : init.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == PUTSTATIC && Set.of(FIELDS).contains(field.name)) {
                MethodInsnNode constructor = (MethodInsnNode) previousCode(field);
                AbstractInsnNode duplicate = previousCode(constructor);
                TypeInsnNode allocation = (TypeInsnNode) previousCode(duplicate);
                require(constructor.owner.equals(QUEUE) && constructor.name.equals("<init>") && duplicate.getOpcode() == DUP &&
                    allocation.getOpcode() == NEW && allocation.desc.equals(QUEUE), "Unknown queue initialization");
                allocation.desc = "pzshield/FairQueue";
                constructor.owner = allocation.desc; constructor.desc = "(I)V";
                int phase = java.util.Arrays.asList(FIELDS).indexOf(field.name);
                init.instructions.insertBefore(constructor, new InsnNode(ICONST_0 + phase));
                ++queues;
            }
        }
        require(queues == 3, "Expected three server queues");
        MethodNode incoming = method(node, "addIncoming", "(SLzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;)V");
        incoming.instructions.clear(); incoming.tryCatchBlocks.clear();
        incoming.localVariables = null;
        incoming.instructions.add(new VarInsnNode(ILOAD, 0));
        incoming.instructions.add(new VarInsnNode(ALOAD, 1));
        incoming.instructions.add(new VarInsnNode(ALOAD, 2));
        for (String field : FIELDS) incoming.instructions.add(new FieldInsnNode(GETSTATIC, GAME, field, QDESC));
        incoming.instructions.add(new MethodInsnNode(INVOKESTATIC, HOOK, "addIncoming",
            "(SLzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;" + QDESC + QDESC + QDESC + ")V", false));
        incoming.instructions.add(new InsnNode(RETURN));
        MethodNode deal = method(node, "mainLoopDealWithNetData", "(Lzombie/network/ZomboidNetData;)V");
        int preAuth = 0;
        for (AbstractInsnNode insn : deal.instructions.toArray())
            if (insn instanceof LdcInsnNode reason && "unacceptable-packet".equals(reason.cst)) {
                deal.instructions.insertBefore(insn, new MethodInsnNode(INVOKESTATIC, HOOK, "preAuthRejected", "()V", false));
                ++preAuth;
            }
        require(preAuth == 1, "Expected vanilla pre-auth rejection site");
        finallyRecycle(deal);
        return write(node);
    }

    private static byte[] patchVehicle(byte[] original) {
        ClassNode node = node(original);
        require(node.methods.stream().noneMatch(m -> m.name.equals("pzshieldValidState")), "Vehicle state hook already exists");
        node.interfaces.add("pzshield/VehicleStateView");
        MethodNode state = new MethodNode(ACC_PUBLIC, "pzshieldValidState", "(I)Z", null, null);
        state.instructions.add(new VarInsnNode(ALOAD, 0));
        state.instructions.add(new FieldInsnNode(GETFIELD, VEHICLE, "wheelsCount", "S"));
        state.instructions.add(new VarInsnNode(ILOAD, 1));
        for (String component : new String[]{"qx", "qy", "qz", "qw"}) {
            state.instructions.add(new VarInsnNode(ALOAD, 0));
            state.instructions.add(new FieldInsnNode(GETFIELD, VEHICLE, component, "F"));
        }
        state.instructions.add(new MethodInsnNode(INVOKESTATIC, HOOK, "vehicleState", "(IIFFFF)Z", false));
        state.instructions.add(new InsnNode(IRETURN));
        node.methods.add(state);
        MethodNode parse = method(node, "parse", "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V");
        int setters = 0;
        for (AbstractInsnNode insn : parse.instructions.toArray())
            if (insn instanceof MethodInsnNode call && call.owner.equals("zombie/vehicles/BaseVehicle") && call.name.equals("setSpeedKmHour")) {
                require(call.desc.equals("(F)V"), "Unexpected speed setter descriptor");
                InsnList arguments = new InsnList();
                arguments.add(new VarInsnNode(ALOAD, 2)); arguments.add(new VarInsnNode(ALOAD, 0));
                parse.instructions.insertBefore(call, arguments);
                parse.instructions.set(call, new MethodInsnNode(INVOKESTATIC, HOOK, "setVehicleSpeed",
                    "(Lzombie/vehicles/BaseVehicle;FLzombie/network/IConnection;L" + VEHICLE + ";)V", false));
                ++setters;
            }
        require(setters == 1, "Expected one speed mutation during vehicle parsing");
        MethodNode process = method(node, "processServer", "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V");
        LabelNode okay = new LabelNode(); InsnList guard = new InsnList();
        guard.add(new VarInsnNode(ALOAD, 0)); guard.add(new VarInsnNode(ALOAD, 2));
        guard.add(new MethodInsnNode(INVOKESTATIC, HOOK, "vehicleOwner", "(L" + VEHICLE + ";Lzombie/core/raknet/UdpConnection;)Z", false));
        guard.add(new JumpInsnNode(IFNE, okay)); guard.add(new InsnNode(RETURN)); guard.add(okay);
        process.instructions.insert(guard);
        return write(node);
    }

    private static void finallyRecycle(MethodNode method) {
        int discarded = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(POOL) && call.name.equals("discard")) {
                AbstractInsnNode load = previousCode(call), instance = previousCode(load);
                require(load instanceof VarInsnNode var && var.var == 0 && load.getOpcode() == ALOAD &&
                    instance instanceof FieldInsnNode field && field.owner.equals(POOL), "Unknown discard site");
                method.instructions.remove(instance); method.instructions.remove(load); method.instructions.remove(call);
                ++discarded;
            }
        }
        require(discarded == 3, "Expected three original discard sites");
        LabelNode start = new LabelNode(), end = new LabelNode(), normal = new LabelNode(), exceptional = new LabelNode();
        method.instructions.insert(start);
        for (AbstractInsnNode insn : method.instructions.toArray())
            if (insn.getOpcode() == RETURN) method.instructions.set(insn, new JumpInsnNode(GOTO, normal));
        method.instructions.add(end);
        method.instructions.add(normal); method.instructions.add(recycle()); method.instructions.add(new InsnNode(RETURN));
        int throwable = method.maxLocals;
        method.instructions.add(exceptional);
        method.instructions.add(new VarInsnNode(ASTORE, throwable));
        method.instructions.add(recycle());
        method.instructions.add(new VarInsnNode(ALOAD, throwable)); method.instructions.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, exceptional, null));
    }

    private static InsnList recycle() {
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(GETSTATIC, POOL, "instance", "L" + POOL + ";"));
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, POOL, "discard", "(L" + DATA + ";)V", false));
        return code;
    }

    private static byte[] patchEngine(byte[] original) {
        ClassNode node = node(original);
        MethodNode decode = method(node, "decode", "(Lzombie/core/network/ByteBufferReader;)V");
        LabelNode okay = new LabelNode(); InsnList guard = new InsnList();
        guard.add(new VarInsnNode(ALOAD, 1)); guard.add(new VarInsnNode(ALOAD, 0));
        guard.add(new FieldInsnNode(GETFIELD, node.name, "server", "Z"));
        guard.add(new MethodInsnNode(INVOKESTATIC, HOOK, "allowDatagram", "(Lzombie/core/network/ByteBufferReader;Z)Z", false));
        guard.add(new JumpInsnNode(IFNE, okay)); guard.add(new InsnNode(RETURN)); guard.add(okay);
        decode.instructions.insert(guard);
        return write(node);
    }

    private static byte[] patchPool(byte[] original) {
        ClassNode node = node(original);
        MethodNode discard = method(node, "discard", "(Lzombie/network/ZomboidNetData;)V");
        InsnList release = new InsnList(); release.add(new VarInsnNode(ALOAD, 1));
        release.add(new MethodInsnNode(INVOKESTATIC, HOOK, "release", "(L" + DATA + ";)V", false));
        discard.instructions.insert(release);
        int pools = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions.toArray())
            if (insn instanceof TypeInsnNode allocation && allocation.getOpcode() == NEW && allocation.desc.equals(QUEUE)) {
                MethodInsnNode constructor = (MethodInsnNode) nextCode(nextCode(allocation));
                require(constructor.name.equals("<init>") && constructor.owner.equals(QUEUE), "Unknown pool constructor");
                allocation.desc = "pzshield/BoundedPoolQueue"; constructor.owner = allocation.desc; ++pools;
            }
        require(pools == 1, "Expected one pooled-buffer queue");
        return write(node);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        require(node.version == V25, "Expected Java 25 class format"); return node;
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing method " + node.name + "." + name + descriptor));
    }
    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected ClassLoader getClassLoader() { return loader; }
        };
        node.accept(writer); return writer.toByteArray();
    }
    private static AbstractInsnNode nextCode(AbstractInsnNode node) {
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0); return node;
    }
    private static AbstractInsnNode previousCode(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0); return node;
    }
    private static byte[] read(JarFile jar, String name) throws Exception {
        try (var stream = jar.getInputStream(jar.getJarEntry(name))) { return stream.readAllBytes(); }
    }
    private static void require(boolean condition, String error) { if (!condition) throw new IllegalStateException(error); }
    private static void verifyArchive(Path input, Path output) throws Exception {
        Set<String> changed = Set.of(GAME + ".class", "zombie/core/raknet/UdpEngine.class", POOL + ".class", VEHICLE + ".class");
        try (JarFile before = new JarFile(input.toFile()); JarFile after = new JarFile(output.toFile())) {
            var entries = before.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                require(after.getJarEntry(entry.getName()) != null, "Missing output entry " + entry.getName());
                if (!changed.contains(entry.getName()))
                    require(java.util.Arrays.equals(read(before, entry.getName()), read(after, entry.getName())), "Unexpected modification " + entry.getName());
            }
        }
    }
}

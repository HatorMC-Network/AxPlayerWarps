# AxAPI Patch Tool

## Why this patch exists

AxPlayerWarps shades AxAPI, which in turn shades PacketEvents.
PacketEvents injects `ChannelDuplexHandlerPacketListener` into every online player's
Netty pipeline. The listener has a static-ish flag `PacketEvents.listening` that is set
to `true` the moment `BuiltinPacketListener` registers on startup (required for the
`SignInput` / `AnvilInput` features). Once raised, **the flag never goes back to `false`**.

This makes the `write()` handler execute the following work for **every outbound packet
sent to every player**, even though it never does anything useful for AxPlayerWarps:

- `transformer.packetId()` — lookup in an NMS map
- `ClientboundPacketTypes.forPacketId()` — a second lookup
- `new PacketEvent(...)` — heap allocation
- `callEvent()` — iterates listeners → `onPacketSending()` → type check → immediate return

`BuiltinPacketListener.onPacketSending()` only acts on `ADD_ENTITY` packets when
`LISTEN_TO_RIDE_PACKET=true`, which defaults to `false`. AxPlayerWarps does not use
PacketEntities at all. The useful-work ratio of all that activity is **~0.002%**.

In a real production profile (Spark, Paper 1.21, ~90 players, 5 minutes), this produced
**~435,000 CPU samples on Netty threads** — a measurable and unnecessary server load.

## What the patch does

The patcher uses ASM to locate `ChannelDuplexHandlerPacketListener` (or its shaded
equivalent — it scans for the class that holds a `boolean listening` field and a
`write(ChannelHandlerContext, Object, ChannelPromise)` method), then makes two changes:

1. **Adds** `private boolean outboundListening` (default `false`).
2. **Rewrites** the start of `write()` to return immediately when `outboundListening`
   is `false`:
   ```java
   if (!this.outboundListening) {
       super.write(ctx, msg, promise);
       return;
   }
   ```

The `channelRead()` / inbound path and the original `listening` field are **not touched**.
Since AxPlayerWarps never calls `enableOutboundListening()`, every call to `write()` now
short-circuits on the first instruction. The ~435K Netty samples drop to near zero.

The correct long-term fix is for Artillex Studios to expose this `inbound/outbound`
distinction in AxAPI upstream. This patch is a working workaround until that happens.

## Building the tool

```bash
cd patch
mvn package
# produces patch/target/patch-tool.jar
```

Requires Java 11+ and Maven 3.x.

## Running the patcher

```bash
java -jar patch/target/patch-tool.jar <original-jar> <output-jar>
```

Example — re-generating the vendored JAR from a freshly downloaded original:

```bash
java -jar patch/target/patch-tool.jar \
  axapi-2.1.0-DEV-31-all.jar \
  local-repo/com/artillexstudios/axapi/axapi/2.1.0-DEV-31/axapi-2.1.0-DEV-31-all.jar
```

The tool will:
- Scan all `.class` entries in the JAR for the target class.
- Inject the `outboundListening` guard and write a patched copy to the output path.
- Exit with a non-zero code and a descriptive message if the target class is not found
  or if the JAR has already been patched.

## When to re-run

Re-run the patcher whenever AxAPI is updated to a new version:

1. Download the new `axapi-<version>-all.jar` from the Artillex Maven repository.
2. Run: `java -jar patch/target/patch-tool.jar axapi-<version>-all.jar <output-path>`.
3. Place the patched JAR at the correct path inside `local-repo/` following Maven's
   directory layout: `local-repo/com/artillexstudios/axapi/axapi/<version>/axapi-<version>-all.jar`.
4. Update the `<version>` in the root `pom.xml` dependency and the `local-repo/` POM file
   if the version number changed.

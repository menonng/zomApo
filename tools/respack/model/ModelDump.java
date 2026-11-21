import java.lang.reflect.Field;
import java.util.*;

/** 바닐라 엔티티 모델 정의(LayerDefinitions)를 JSON 으로. 실행하는 사람의 jar 에서 읽는다. */
public class ModelDump {
    static Object get(Object o, String f) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try { Field fd = c.getDeclaredField(f); fd.setAccessible(true); return fd.get(o); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(f);
    }
    static float fl(Object o, String f) throws Exception { return ((Number) get(o, f)).floatValue(); }
    static String vec(Object v) throws Exception {
        org.joml.Vector3fc x = (org.joml.Vector3fc) v; return "[" + x.x() + "," + x.y() + "," + x.z() + "]";
    }
    static void part(StringBuilder sb, String name, Object p) throws Exception {
        Object pose = get(p, "partPose");
        sb.append("{\"name\":\"").append(name).append("\",\"pose\":[")
          .append(fl(pose, "x")).append(',').append(fl(pose, "y")).append(',').append(fl(pose, "z")).append(',')
          .append(fl(pose, "xRot")).append(',').append(fl(pose, "yRot")).append(',').append(fl(pose, "zRot")).append(',')
          .append(fl(pose, "xScale")).append(',').append(fl(pose, "yScale")).append(',').append(fl(pose, "zScale")).append("],\"cubes\":[");
        boolean first = true;
        for (Object c : (List<?>) get(p, "cubes")) {
            if (!first) sb.append(','); first = false;
            Object g = get(c, "grow"); Object uv = get(c, "texCoord"); Object ts = get(c, "texScale");
            Set<?> faces = (Set<?>) get(c, "visibleFaces");
            sb.append("{\"origin\":").append(vec(get(c, "origin"))).append(",\"size\":").append(vec(get(c, "dimensions")))
              .append(",\"grow\":[").append(fl(g, "growX")).append(',').append(fl(g, "growY")).append(',').append(fl(g, "growZ"))
              .append("],\"mirror\":").append(get(c, "mirror")).append(",\"uv\":[").append(fl(uv, "u")).append(',').append(fl(uv, "v"))
              .append("],\"texScale\":[").append(fl(ts, "u")).append(',').append(fl(ts, "v")).append("],\"faces\":[");
            boolean f2 = true;
            for (Object d : faces) { if (!f2) sb.append(','); f2 = false; sb.append('"').append(d.toString()).append('"'); }
            sb.append("]}");
        }
        sb.append("],\"children\":[");
        first = true;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) get(p, "children")).entrySet()) {
            if (!first) sb.append(','); first = false;
            part(sb, e.getKey().toString(), e.getValue());
        }
        sb.append("]}");
    }
    public static void main(String[] a) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Set<String> want = new HashSet<>(Arrays.asList(a));
        Map<?, ?> roots = net.minecraft.client.model.geom.LayerDefinitions.createRoots();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : roots.entrySet()) {
            String key = e.getKey().toString(); // 예: minecraft:creeper#main
            if (!want.isEmpty() && want.stream().noneMatch(key::startsWith)) continue;
            Object mesh = get(e.getValue(), "mesh"); Object mat = get(e.getValue(), "material");
            if (!first) sb.append(','); first = false;
            sb.append('"').append(key).append("\":{\"tex\":[").append(get(mat, "xTexSize")).append(',').append(get(mat, "yTexSize")).append("],\"root\":");
            part(sb, "root", get(mesh, "root"));
            sb.append('}');
        }
        sb.append('}');
        java.nio.file.Files.writeString(java.nio.file.Path.of("models.json"), sb.toString());
        System.out.println("ok " + roots.size());
    }
}

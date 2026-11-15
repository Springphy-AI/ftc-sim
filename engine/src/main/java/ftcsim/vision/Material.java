package ftcsim.vision;

/** How the camera renderer colors a triangle. */
final class Material {

    enum Kind {
        /** One color, lit by the arena lights. */
        FLAT,
        /** An ARGB image mapped by per-vertex texture coordinates (AprilTags); not lit. */
        TEXTURE,
        /** The field floor: tiles, seams and tape, colored per pixel from the world position. */
        FLOOR
    }

    final Kind kind;
    final int rgb;
    final float alpha;
    final int[] texture;
    final int textureSize;

    private Material(Kind kind, int rgb, float alpha, int[] texture, int textureSize) {
        this.kind = kind;
        this.rgb = rgb;
        this.alpha = alpha;
        this.texture = texture;
        this.textureSize = textureSize;
    }

    static Material flat(int rgb) {
        return new Material(Kind.FLAT, rgb, 1f, null, 0);
    }

    /** A see-through surface (polycarbonate walls, the HIVE CELLS). */
    static Material translucent(int rgb, float alpha) {
        return new Material(Kind.FLAT, rgb, alpha, null, 0);
    }

    /** A square ARGB texture, {@code size} x {@code size} texels. */
    static Material texture(int[] argb, int size) {
        return new Material(Kind.TEXTURE, 0xffffff, 1f, argb, size);
    }

    static Material floor() {
        return new Material(Kind.FLOOR, 0x50555c, 1f, null, 0);
    }

    boolean opaque() {
        return alpha >= 0.999f;
    }
}

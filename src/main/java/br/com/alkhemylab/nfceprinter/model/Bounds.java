package br.com.alkhemylab.nfceprinter.model;

public record Bounds(float left, float top, float right, float bottom) {
    public Bounds {
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("Limites invalidos.");
        }
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }

    public Bounds union(Bounds other) {
        return new Bounds(
                Math.min(left, other.left),
                Math.min(top, other.top),
                Math.max(right, other.right),
                Math.max(bottom, other.bottom));
    }
}

package de.codesourcery.metaballs;

public class Vec2d {

	public float x;
	public float y;

	public Vec2d(Vec2d other) {
		this.x = other.x;
		this.y = other.y;
	}

	public Vec2d(float x, float y) {
		super();
		this.x = x;
		this.y = y;
	}

	public void add(Vec2d v) {
		this.x += v.x;
		this.y += v.y;
	}

	public void sub(Vec2d v) {
		this.x -= v.x;
		this.y -= v.y;
	}

	public void scl(float f) {
		this.x *= f;
		this.y *= f;
	}

	public void set(Vec2d position) {
		this.x = position.x;
		this.y = position.y;
	}
}
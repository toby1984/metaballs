package de.codesourcery.metaballs;

public class MetaBall {

	public final Vec2d position = new Vec2d(0,0);
	public final float radiusSquared;
	public final float radius;
	public final Vec2d velocity = new Vec2d(0,0);

	public MetaBall(Vec2d position,float radius,Vec2d velocity) {
		this.position.set(position);
		this.radius = radius;
		this.radiusSquared = radius*radius;
		this.velocity.set(velocity);
	}

	public void move(Vec2d min,Vec2d max) {

		float newX = position.x + velocity.x;
		if ( (newX-radius) < min.x ) {
			newX = position.x - velocity.x;
			velocity.x = -velocity.x;
		} else if ( (newX+radius) > max.x ) {
			newX = position.x - velocity.x;
			velocity.x = -velocity.x;
		}

		float newY = position.y + velocity.y;
		if ( (newY-radius) < min.y ) {
			newY = position.y - velocity.y;
			velocity.y = -velocity.y;
		} else if ( (newY+radius) > max.y ) {
			newY = position.y - velocity.y;
			velocity.y = -velocity.y;
		}
		position.x = newX;
		position.y = newY;
	}
}
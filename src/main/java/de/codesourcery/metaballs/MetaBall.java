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

	public float dst2(float x,float y)
	{
		final float dx = position.x - x;
		final float dy = position.y - y;
		return dx*dx+dy*dy;
	}

	public void move(Vec2d min,Vec2d max,float deltaSeconds) {

		float dx = deltaSeconds*velocity.x;
		float dy = deltaSeconds*velocity.y;
		
		float newX = position.x + dx;
		float newY = position.y + dy;
		
		if ( (newX-radius) < min.x ) {
			newX = position.x - dx;
			velocity.x = -velocity.x;
		} else if ( (newX+radius) > max.x ) {
			newX = position.x - dx;
			velocity.x = -velocity.x;
		}


		if ( (newY-radius) < min.y ) {
			newY = position.y - dy;
			velocity.y = -velocity.y;
		} else if ( (newY+radius) > max.y ) {
			newY = position.y - dy;
			velocity.y = -velocity.y;
		}
		position.x = newX;
		position.y = newY;
	}
}
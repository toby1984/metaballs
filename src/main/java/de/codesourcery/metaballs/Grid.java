package de.codesourcery.metaballs;

import java.util.ArrayList;
import java.util.List;

public class Grid {

	private final int rows;
	private final int columns;

	private final int deltaX;
	private final int deltaY;

	private final float cellWidth;
	private final float cellHeight;

	private final float centerX;
	private final float centerY;

	private final List<MetaBall> metaBalls = new ArrayList<>();

	private final float neighbourRadiusSquared;

	private final List<MetaBall>[][] grid;

	public interface IVisitor
	{
		public void visit(MetaBall ball);

		public float getResult();
	}

	public Grid(float width, float height)
	{
		this.neighbourRadiusSquared = 0.10f*( (width/2)*(width/2)+(height/2)*(height/2) );
		final float neighbourRadius = (float) Math.sqrt( neighbourRadiusSquared );

		this.rows = (int) Math.ceil( width / neighbourRadius );
		this.columns = (int) Math.ceil( height / neighbourRadius );

		System.out.println("Rows / columns: "+rows+" x "+columns);

		this.cellWidth = width / columns;
		this.cellHeight = height / rows;

		this.centerX = width / 2f;
		this.centerY = height / 2f;

		this.deltaX = (int) Math.ceil( neighbourRadius / this.cellWidth );
		this.deltaY = (int) Math.ceil( neighbourRadius / this.cellHeight );

		System.out.println("DX = "+deltaX+" / DY = "+deltaY );

		grid = new List[columns][rows];
		for ( int x = 0 ; x < columns ; x++ )
		{
			for ( int y = 0 ; y < rows ; y++ )
			{
				grid[x][y] = new ArrayList<>(60);
			}
		}
	}

	public void add(List<MetaBall> balls)
	{
		this.metaBalls.clear();
		this.metaBalls.addAll( balls );
		refresh();
	}

	public void refresh() {

		for ( int x = 0 ; x < columns ; x++ )
		{
			for ( int y = 0 ; y < rows ; y++ )
			{
				grid[x][y].clear();
			}
		}

		for ( final MetaBall mb : metaBalls )
		{
			final int ix = (int) ((mb.position.x+centerX) / cellWidth);
			final int iy = (int) ((mb.position.y+centerY) / cellHeight);
			grid[ix][iy].add( mb );
		}
	}

	public void visitAll(IVisitor visitor)
	{
		final int len = this.metaBalls.size();
		final List<MetaBall> metaBalls2 = this.metaBalls;

		for (int i = 0; i < len ; i++) {
			visitor.visit( metaBalls2.get(i) );
		}
	}

	public void visitClosest(float px,float py,IVisitor visitor)
	{
		final int ix = (int) ((px+centerX) / cellWidth);
		final int iy = (int) ((py+centerY) / cellHeight);

		final int xEnd = Math.min(ix+deltaX, columns-1 );
		final int yEnd = Math.min(iy+deltaY , rows-1 );

		for ( int x = Math.max( ix-deltaX , 0 ) ; x <= xEnd ; x++ )
		{
			for ( int y = Math.max( iy-1  , 0 ); y <= yEnd ; y++ )
			{
				final List<MetaBall> list = grid[x][y];
				final int len = list.size();
				for (int i = 0; i < len ; i++)
				{
					final MetaBall mb = list.get(i);
					if ( mb.dst2( px , py ) <= neighbourRadiusSquared ) {
						visitor.visit( mb );
					}
				}
			}
		}
	}
}

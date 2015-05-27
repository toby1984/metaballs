package de.codesourcery.metaballs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBufferInt;
import java.awt.image.Kernel;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import de.codesourcery.metaballs.Grid.IVisitor;

public final class Main extends JFrame {

	public static final int BALL_COUNT =40;

	public static final float MIN_RADIUS = 5;
	public static final float MAX_RADIUS = 30;

	public static final float MIN_VELOCITY = 0.1f;
	public static final float MAX_VELOCITY = 50.0f;

	public static final float MODEL_WIDTH  = 800;
	public static final float MODEL_HEIGHT = 600;

	public static final float HALF_MODEL_WIDTH  = MODEL_WIDTH/2.0f;
	public static final float HALF_MODEL_HEIGHT = MODEL_HEIGHT/2.0f;

	public static final Vec2d MIN = new Vec2d(-HALF_MODEL_WIDTH , -HALF_MODEL_HEIGHT );
	public static final Vec2d MAX = new Vec2d( HALF_MODEL_WIDTH ,  HALF_MODEL_HEIGHT );

	public static final int BENCHMARK_FRAMES = 2000;
	public static final boolean BENCHMARK_MODE = false;

	protected final long startup = System.currentTimeMillis();

	protected final Grid grid = new Grid( MODEL_WIDTH , MODEL_HEIGHT );

	public Main() {
		super("metaballs");
	}

	public static void main(String[] args) throws Exception {
		new Main().run();
	}

	protected final class MyPanel extends JPanel {

		protected final Object BUFFER_LOCK = new Object();

		// @GuardedBy( BUFFER_LOCK )
		protected BufferedImage[] bufferImage;
		// @GuardedBy( BUFFER_LOCK )
		protected Graphics2D[] bufferGraphics;
		// @GuardedBy( BUFFER_LOCK )
		protected int currentBufferIdx;
		// @GuardedBy( BUFFER_LOCK )
		protected BufferedImage tmpImage;

		protected volatile boolean gaussianBlur = true;
		protected volatile boolean useGradient = false;

		protected final int sliceCount;

		protected final int[] colors = new int[256];

		protected final ThreadPoolExecutor threadPool;
		
		private ConvolveOp horizontalBlur;
		private ConvolveOp verticalBlur;

		public MyPanel()
		{
			setFocusable(true);
			setRequestFocusEnabled(true);

			addKeyListener( new KeyAdapter() {
				@Override
				public void keyTyped(KeyEvent e)
				{
					if ( e.getKeyChar() == 'g' ) {
						useGradient = !useGradient;
					} else if ( e.getKeyChar() == 'b' ) {
						gaussianBlur = !gaussianBlur;
					}
				}
			});

			horizontalBlur = createGaussianBlurFilter( 2 , true );
			verticalBlur = createGaussianBlurFilter( 2 , false );
			
			// setup colors
			for ( int i=0; i < 256 ; i++ )
			{
				final int r = i;
				final int g = 0;
				final int b = 0;
				colors[i] = r << 16 | g << 8 | b;
			}

			// setup worker thread pool
			final BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>( 100 );
			final ThreadFactory threadFactory = new ThreadFactory()
			{

				private final AtomicInteger threadId = new AtomicInteger();
				@Override
				public Thread newThread(Runnable r)
				{
					final Thread t = new Thread(r);
					t.setDaemon(true);
					t.setName("calculation-thread-"+threadId.incrementAndGet());
					return t;
				}
			};

			final int cpuCount = Runtime.getRuntime().availableProcessors();
			threadPool = new ThreadPoolExecutor(cpuCount, cpuCount, 60, TimeUnit.SECONDS, workQueue, threadFactory,new ThreadPoolExecutor.CallerRunsPolicy() );

			sliceCount = cpuCount;
			System.out.println("Using "+cpuCount+" threads and "+sliceCount+" slices");
		}
		
		/**
		 * Kernel calculation taken from http://www.java2s.com/Code/Java/Advanced-Graphics/GaussianBlurDemo.htm
		 * 
		 * Copyright (c) 2007, Romain Guy
		 * 
		 * @param radius
		 * @param horizontal
		 * @return
		 */
		 protected ConvolveOp createGaussianBlurFilter(int radius,boolean horizontal) 
		 {
		        if (radius < 1) {
		            throw new IllegalArgumentException("Radius must be >= 1");
		        }
		        
		        int size = radius * 2 + 1;
		        System.out.println("Blur kernel size: "+size+"x"+size);
		        float[] data = new float[size];
		        
		        float sigma = radius / 3.0f;
		        float twoSigmaSquare = 2.0f * sigma * sigma;
		        float sigmaRoot = (float) Math.sqrt(twoSigmaSquare * Math.PI);
		        float total = 0.0f;
		        
		        for (int i = -radius; i <= radius; i++) {
		            float distance = i * i;
		            int index = i + radius;
		            data[index] = (float) Math.exp(-distance / twoSigmaSquare) / sigmaRoot;
		            total += data[index];
		        }
		        
		        for (int i = 0; i < data.length; i++) {
		            data[i] /= total;
		        }        
		        
		        Kernel kernel = null;
		        if (horizontal) {
		            kernel = new Kernel(size, 1, data);
		        } else {
		            kernel = new Kernel(1, size, data);
		        }
		        return new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
		    }		

		protected int[] getPixelDataArray(BufferedImage bufferImage) {
			return ((DataBufferInt) bufferImage.getRaster().getDataBuffer()).getData();
		}

		private void maybeInit() 
		{
			final int w = getWidth();
			final int h = getHeight();

			if ( bufferImage == null ) 
			{
				if ( bufferGraphics != null ) 
				{
					bufferGraphics[0].dispose();
					bufferGraphics[1].dispose();
				} else {
					bufferImage = new BufferedImage[2];
					bufferGraphics = new Graphics2D[2];
				}
				
				bufferImage[0] = createBufferedImage((int) MODEL_WIDTH , (int) MODEL_HEIGHT );// new BufferedImage( (int) MODEL_WIDTH , (int) MODEL_HEIGHT , BufferedImage.TYPE_INT_RGB );
				bufferImage[1] = createBufferedImage((int) MODEL_WIDTH , (int) MODEL_HEIGHT );
				tmpImage = createBufferedImage((int) MODEL_WIDTH , (int) MODEL_HEIGHT );
				
				bufferGraphics[0] = bufferImage[0].createGraphics();
				bufferGraphics[1] = bufferImage[1].createGraphics();

				bufferGraphics[0].setColor( Color.WHITE );
				bufferGraphics[0].fillRect(0 , 0, w , h );

				bufferGraphics[1].setColor( Color.WHITE );
				bufferGraphics[1].fillRect(0 , 0, w , h );					
			}
		}

		public void render(float deltaSeconds) 
		{
			synchronized ( BUFFER_LOCK ) 
			{
				maybeInit();
				
				final float w = MODEL_WIDTH;
				final float h = MODEL_HEIGHT;

				final int width = (int) MODEL_WIDTH;
				final int height = (int) MODEL_HEIGHT;
				
				final BufferedImage bufferImage = getBackBuffer();

				final Graphics2D graphics = getBackBufferGraphics();
				graphics.setColor( Color.WHITE );
				graphics.fillRect(0 , 0, bufferImage.getWidth() , bufferImage.getHeight() );					

				final List<Rectangle> slices = createSlices( width , height , sliceCount );
				final CountDownLatch latch = new CountDownLatch( slices.size() );
				for ( final Rectangle r : slices )
				{
					final Runnable runnable = new Runnable()
					{
						@Override
						public void run() {
							try
							{
								final int startX = r.x;
								final int startY = r.y;

								final int endX = startX + r.width;
								final int endY = startY + r.height;

								final int[] pixelData = getPixelDataArray( bufferImage );

								final int intsPerLine = bufferImage.getWidth();
								final boolean USE_GRADIENT = useGradient;
								for ( int y = startY ; y < endY ; y++ )
								{
									final float modelY = ( y/h - 0.5f ) * MODEL_HEIGHT;
									int offset = startX + y*intsPerLine;
									for ( int x = startX ; x < endX ; x++,offset++ )
									{
										final float modelX = ( x/w - 0.5f ) * MODEL_WIDTH;

										final float density = getDensity( modelX ,  modelY );
										if ( USE_GRADIENT || density > 1.5f )
										{
											int colorIdx = (int) ( (1-(1f/density))*255f );
											if ( USE_GRADIENT )
											{
												if ( colorIdx > 255 )
												{
													colorIdx = 255;
												}
												else if ( colorIdx < 0 )
												{
													colorIdx = 0;
												}
											} else {
												colorIdx = 255;
											}
											pixelData[offset] = colors[ colorIdx ];
										}
									}
								}
							} finally {
								latch.countDown();
							}
						}

					};

					 threadPool.submit( runnable );
				}

				try
				{
					latch.await();
				} catch (final InterruptedException e) {
					e.printStackTrace();
				}	

				if ( gaussianBlur ) {
					horizontalBlur.filter( bufferImage , tmpImage );
					verticalBlur.filter( tmpImage , bufferImage );
				}
				swapBuffers();
			}
		}
		
		private BufferedImage createBufferedImage(int width,int height) {
			final GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
			final GraphicsConfiguration gc = ge.getDefaultScreenDevice().getDefaultConfiguration();
			final BufferedImage img = gc.createCompatibleImage(width, height, Transparency.OPAQUE);
			img.setAccelerationPriority(1);
			return img;
		}

		private BufferedImage getFrontBuffer() {
			return bufferImage[ (currentBufferIdx+1) % 2 ];
		}

		private Graphics2D getBackBufferGraphics() {
			return bufferGraphics[ currentBufferIdx ];
		}	

		private BufferedImage getBackBuffer() {
			return bufferImage[ currentBufferIdx ];
		}		

		private void swapBuffers() {
			currentBufferIdx = (currentBufferIdx+1) % 2;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			synchronized (BUFFER_LOCK) {
				maybeInit();
				g.drawImage( getFrontBuffer() , 0 , 0 , getWidth() , getHeight() , null ); // blit image
			}
			Toolkit.getDefaultToolkit().sync();
		}

		protected float getDensity(float x,float y)
		{
			final IVisitor densityCalculator = new IVisitor() {

				private float sum = 0;

				@Override
				public void visit(MetaBall ball)
				{
					final float dx = x - ball.position.x;
					final float dy = y - ball.position.y;
					final float squaredDistance = dx*dx + dy*dy;
					sum += ball.radiusSquared / squaredDistance;
				}

				@Override
				public float getResult() {
					return sum;
				}
			};

			grid.visitClosest( x , y ,  densityCalculator );
			return densityCalculator.getResult();
		}
	}

	private List<Rectangle> slices;
	private int slicesWidth = -1;
	private int slicesHeight = -1;
	private int sliceCount = -1;

	private List<Rectangle> createSlices(int width,int height,int numberOfSlices)
	{
		if ( slices != null && slicesWidth == width && slicesHeight == height && numberOfSlices == sliceCount)
		{
			return slices;
		}

		final int slicesPerRow = (int) Math.ceil( Math.sqrt( numberOfSlices ) );
		final int slicesPerColumn = slicesPerRow;

		final int sliceWidth = width / slicesPerRow;
		final int sliceHeight = height / slicesPerColumn;

		final List<Rectangle> result = new ArrayList<>(sliceHeight*slicesPerRow);

		int x = 0;
		int y = 0;
		while ( y < height )
		{
			x = 0;
			final int h = (y+sliceHeight) < height ? sliceHeight : height - y;
			while (  x < width  )
			{
				final int w = (x+sliceWidth) < width ? sliceWidth : width - x;
				result.add( new Rectangle( x ,y , w  , h ) );
				x += sliceWidth;
			}
			y += h;
		}

		this.slicesWidth = width;
		this.slicesHeight = height;
		this.sliceCount = numberOfSlices;
		this.slices = result;

		return result;
	}

	public void run() throws InvocationTargetException, InterruptedException
	{
		// setup random meta-balls
		final Random rnd = new Random(System.currentTimeMillis());
		final List<MetaBall> metaBalls = new ArrayList<>( BALL_COUNT );
		for ( int i = 0 ; i < BALL_COUNT ; i++ )
		{
			final float r = MIN_RADIUS + rnd.nextFloat()*(MAX_RADIUS-MIN_RADIUS);
			final float tx = rnd.nextFloat() * (MODEL_WIDTH-r);
			final float ty = rnd.nextFloat() * (MODEL_HEIGHT-r);
			final float x = tx - (MODEL_WIDTH/2.0f);
			final float y = ty - (MODEL_HEIGHT/2.0f);

			final float vx = MIN_VELOCITY + rnd.nextFloat()*(MAX_VELOCITY-MIN_VELOCITY);
			final float vy = MIN_VELOCITY + rnd.nextFloat()*(MAX_VELOCITY-MIN_VELOCITY);
			metaBalls.add( new MetaBall(new Vec2d(x,y), r , new Vec2d(vx,vy ) ) );
		}

		grid.add( metaBalls );
		grid.refresh();

		final MyPanel panel = new MyPanel();
		panel.setPreferredSize( new Dimension(600,400 ) );
		panel.setSize( new Dimension(600,400 ) );

		final AtomicBoolean paused = new AtomicBoolean(false);

		final KeyAdapter listener = new KeyAdapter() {
			@Override
			public void keyTyped(KeyEvent e) {
				if ( e.getKeyChar() == ' ') {
					paused.set( ! paused.get() );
				}
			}
		};

		addKeyListener( listener );
		panel.addKeyListener( listener);
		panel.setRequestFocusEnabled(true);
		panel.requestFocus();

		getContentPane().setLayout( new BorderLayout() );
		getContentPane().add( panel , BorderLayout.CENTER );

		pack();
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setLocationRelativeTo( null );

		setVisible(true);

		long previous = System.nanoTime();
		float agg = 0;
		while ( true ) 
		{
			long now = System.nanoTime();
			final float elapsedSeconds = Math.abs( now - previous ) / 1_000_000_000f;
			previous = now;
			agg += elapsedSeconds;
			
			final IVisitor mover = new IVisitor() {

				@Override
				public void visit(MetaBall ball) {
					ball.move( MIN, MAX , elapsedSeconds );
				}

				@Override
				public float getResult() { return 0; }
			};
			
			if ( ! paused.get() ) {
				grid.visitAll( mover );
				grid.refresh();
			}
			
			panel.render( elapsedSeconds );
			
			if ( agg > 1.0f/60f) 
			{
				SwingUtilities.invokeAndWait( () -> panel.repaint() );
				agg -= 1.0f/60f;
			}
		}
	}
}
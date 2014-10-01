package de.codesourcery.metaballs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
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
import javax.swing.Timer;

public final class Main extends JFrame {

	public static final int BALL_COUNT =40;

	public static final float MIN_RADIUS = 5;
	public static final float MAX_RADIUS = 30;

	public static final float MIN_VELOCITY = 0.1f;
	public static final float MAX_VELOCITY = 2.0f;

	public static final float MODEL_WIDTH  = 640;
	public static final float MODEL_HEIGHT = 480;

	public static final float HALF_MODEL_WIDTH  = MODEL_WIDTH/2.0f;
	public static final float HALF_MODEL_HEIGHT = MODEL_HEIGHT/2.0f;

	public static final Vec2d MIN = new Vec2d(-HALF_MODEL_WIDTH , -HALF_MODEL_HEIGHT );
	public static final Vec2d MAX = new Vec2d( HALF_MODEL_WIDTH ,  HALF_MODEL_HEIGHT );

	public static final int BENCHMARK_FRAMES = 2000;
	public static final boolean BENCHMARK_MODE = false;

	protected MetaBall[] metaBalls = new MetaBall[0];

	protected final long startup = System.currentTimeMillis();

	public Main() {
		super("metaballs");
	}

	public static void main(String[] args) {
		new Main().run();
	}

	protected final class MyPanel extends JPanel {

		protected int frameCount;
		protected long totalTime = 0;

		protected final BufferedImage bufferImage;
		protected final Graphics2D bufferGraphics;

		protected volatile boolean useGradient = false;

		protected final int sliceCount;

		protected final int[] colors = new int[256];

		protected final ThreadPoolExecutor threadPool;

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
					}
				}
			});

			// setup colors
			for ( int i=0; i < 256 ; i++ )
			{
				final int r = i;
				final int g = 0;
				final int b = 0;
				colors[i] = r << 16 | g << 8 | b;
			}

			// setup background image
			bufferImage = new BufferedImage( (int) MODEL_WIDTH , (int) MODEL_HEIGHT , BufferedImage.TYPE_INT_RGB );
			bufferGraphics = bufferImage.createGraphics();

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

		protected int[] getPixelDataArray() {
			return ((DataBufferInt) bufferImage.getRaster().getDataBuffer()).getData();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final long startTime = System.currentTimeMillis();

			final float w = MODEL_WIDTH;
			final float h = MODEL_HEIGHT;

			final int width = (int) MODEL_WIDTH;
			final int height = (int) MODEL_HEIGHT;

			final long calcStart = System.currentTimeMillis();
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

							final int[] pixelData = getPixelDataArray();

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
									if ( USE_GRADIENT || density > 1 )
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
			final long now = System.currentTimeMillis();
			final long calcTime = now - calcStart;

			// render stuff

			//			bufferGraphics.setColor(Color.BLUE);
			//			for ( final Rectangle r : slices ) {
			//				bufferGraphics.drawRect( r.x , r.y , r.width , r.height );
			//			}

			g.drawImage( bufferImage , 0 , 0 , getWidth() , getHeight() , null ); // blit image

			bufferGraphics.setColor(Color.WHITE);
			bufferGraphics.fillRect( 0 , 0 , width ,height );

			final long now2 = System.currentTimeMillis();
			final long renderTime = now2- now;
			final long delta = now2 - startTime;

			totalTime += delta;
			frameCount++;
			final int fps = (int) ( 1000.0f/ ( totalTime / (float) frameCount ) );

			g.setColor( Color.GREEN );
			final StringBuilder stringBuilder = new StringBuilder().append("FPS: ").append(fps).append(" (").append(delta).append(" ms total, ").append(calcTime ).append(" ms calc, ").append(renderTime).append(" ms rendering)  |  Press 'g' to toggle gradient display  |  Press <SPACE> to halt animation");
			g.drawString(stringBuilder.toString(),10,10);

			// debugging
			if ( BENCHMARK_MODE && frameCount >= BENCHMARK_FRAMES ) {
				final long timeDelta = System.currentTimeMillis() - startup;
				final int fps2 = (int) ( 1000.0f/ ( timeDelta / (float) frameCount ) );
				System.out.println("Rendered "+frameCount+" frames in "+(timeDelta/1000f)+" seconds , "+fps2+" fps");
				System.out.println("Benchmark finished.");
				System.exit(0);
			}
		}

		protected float getDensity(float x,float y)
		{
			float sum = 0;
			for ( int i = 0 ; i < BALL_COUNT ; i++ )
			{
				final MetaBall ball = metaBalls[i];
				final float dx = x - ball.position.x;
				final float dy = y - ball.position.y;
				final float squaredDistance = dx*dx + dy*dy;
				sum += ball.radiusSquared / squaredDistance;
			}
			return sum;
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

	public void run()
	{
		// setup random meta-balls
		final Random rnd = new Random(System.currentTimeMillis());
		metaBalls = new MetaBall[ BALL_COUNT ];
		for ( int i = 0 ; i < BALL_COUNT ; i++ )
		{
			final float r = MIN_RADIUS + rnd.nextFloat()*(MAX_RADIUS-MIN_RADIUS);
			final float x = (rnd.nextFloat() * MODEL_WIDTH) - (MODEL_WIDTH/2.0f);
			final float y = (rnd.nextFloat() * MODEL_HEIGHT) - (MODEL_HEIGHT/2.0f);

			final float vx = MIN_VELOCITY + rnd.nextFloat()*(MAX_VELOCITY-MIN_VELOCITY);
			final float vy = MIN_VELOCITY + rnd.nextFloat()*(MAX_VELOCITY-MIN_VELOCITY);
			metaBalls[i] = new MetaBall(new Vec2d(x,y), r , new Vec2d(vx,vy ) );
		}

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

		final Timer timer = new Timer(16, event ->
		{
			if ( ! paused.get() )
			{
				for ( int i = 0 ; i < BALL_COUNT ; i++ )
				{
					final MetaBall ball = metaBalls[i];
					ball.move( MIN , MAX);
				}
				panel.repaint();
			}
		});
		timer.start();
	}
}
/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2009 - 2012 by Eric House (xwords@eehouse.org).  All
 * rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.eehouse.android.xw4;

import android.app.Activity;
import android.view.View;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.content.Context;
import android.util.AttributeSet;
import org.eehouse.android.xw4.jni.*;
import android.view.MotionEvent;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;
import android.graphics.Paint.FontMetricsInt;
import android.os.Build;
import java.nio.IntBuffer;
import android.util.FloatMath;

import junit.framework.Assert;

public class BoardView extends View implements BoardHandler, SyncedDraw {

    private static final float MIN_FONT_DIPS = 10.0f;
    private static final int MULTI_INACTIVE = -1;

    private static Bitmap s_bitmap;    // the board
    private static final int PINCH_THRESHOLD = 40;

    private Context m_context;
    private int m_defaultFontHt;
    private int m_mediumFontHt;
    private Runnable m_invalidator;
    private int m_jniGamePtr;
    private CurGameInfo m_gi;
    private int m_layoutWidth;
    private int m_layoutHeight;
    private BoardCanvas m_canvas;    // owns the bitmap
    private JNIThread m_jniThread;
    private Activity m_parent;
    private boolean m_measuredFromDims = false;
    private BoardDims m_dims;
    private CommsAddrRec.CommsConnType m_connType = 
        CommsAddrRec.CommsConnType.COMMS_CONN_NONE;

    private int m_lastSpacing = MULTI_INACTIVE;


    // called when inflating xml
    public BoardView( Context context, AttributeSet attrs ) 
    {
        super( context, attrs );

        m_context = context;

        final float scale = getResources().getDisplayMetrics().density;
        m_defaultFontHt = (int)(MIN_FONT_DIPS * scale + 0.5f);
        m_mediumFontHt = m_defaultFontHt * 3 / 2;
        m_invalidator = new Runnable() {
                public void run() {
                    invalidate();
                }
            };
    }

    @Override
    public boolean onTouchEvent( MotionEvent event ) 
    {
        boolean wantMore = null != m_jniThread;
        if ( wantMore ) {
            int action = event.getAction();
            int xx = (int)event.getX();
            int yy = (int)event.getY();
        
            switch ( action ) {
            case MotionEvent.ACTION_DOWN:
                m_lastSpacing = MULTI_INACTIVE;
                if ( !ConnStatusHandler.handleDown( xx, yy ) ) {
                    m_jniThread.handle( JNIThread.JNICmd.CMD_PEN_DOWN, xx, yy );
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if ( ConnStatusHandler.handleMove( xx, yy ) ) {
                } else if ( MULTI_INACTIVE == m_lastSpacing ) {
                    m_jniThread.handle( JNIThread.JNICmd.CMD_PEN_MOVE, xx, yy );
                } else {
                    int zoomBy = figureZoom( event );
                    if ( 0 != zoomBy ) {
                        m_jniThread.handle( JNIThread.JNICmd.CMD_ZOOM, 
                                            zoomBy < 0 ? -2 : 2 );
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if ( ConnStatusHandler.handleUp( xx, yy ) ) {
                    // do nothing
                } else {
                    m_jniThread.handle( JNIThread.JNICmd.CMD_PEN_UP, xx, yy );
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_2_DOWN:
                m_jniThread.handle( JNIThread.JNICmd.CMD_PEN_UP, xx, yy );
                m_lastSpacing = getSpacing( event );
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_POINTER_2_UP:
                m_lastSpacing = MULTI_INACTIVE;
                break;
            default:
                DbgUtils.logf( "onTouchEvent: unknown action: %d", action );
                break;
            }
        }
        return wantMore;            // true required to get subsequent events
    }

    // private void printMode( String comment, int mode )
    // {
    //     comment += ": ";
    //     switch( mode ) {
    //     case View.MeasureSpec.AT_MOST:
    //         comment += "AT_MOST";
    //         break;
    //     case View.MeasureSpec.EXACTLY:
    //         comment += "EXACTLY";
    //         break;
    //     case View.MeasureSpec.UNSPECIFIED:
    //         comment += "UNSPECIFIED";
    //         break;
    //     default:
    //         comment += "<bogus>";
    //     }
    //     DbgUtils.logf( comment );
    // }

    @Override
    protected void onMeasure( int widthMeasureSpec, int heightMeasureSpec )
    {
        // One of the android sample apps ignores mode entirely:
        // int w = MeasureSpec.getSize(widthMeasureSpec);
        // int h = MeasureSpec.getSize(heightMeasureSpec);
        // int d = w == 0 ? h : h == 0 ? w : w < h ? w : h;
        // setMeasuredDimension(d, d);

        int width, height;
        m_measuredFromDims = null != m_dims;
        if ( m_measuredFromDims ) {
            height = m_dims.height;
            width = m_dims.width;
        } else {
            width = View.MeasureSpec.getSize( widthMeasureSpec );
            height = View.MeasureSpec.getSize( heightMeasureSpec );
        }

        int minHeight = getSuggestedMinimumHeight();
        if ( height < minHeight ) {
            height = minHeight;
        }
        int minWidth = getSuggestedMinimumWidth();
        if ( width < minWidth ) {
            width = minWidth;
        }
        setMeasuredDimension( width, height );
    }

    // This will be called from the UI thread
    @Override
    protected void onDraw( Canvas canvas ) 
    {
        synchronized( this ) {
            if ( layoutBoardOnce() && m_measuredFromDims ) {
                canvas.drawBitmap( s_bitmap, 0, 0, new Paint() );
                ConnStatusHandler.draw( m_context, canvas, getResources(), 
                                        0, 0, m_connType );
            } else {
                DbgUtils.logf( "board not laid out yet" );
            }
        }
    }

    private boolean layoutBoardOnce() 
    {
        final int width = getWidth();
        final int height = getHeight();
        boolean layoutDone = width == m_layoutWidth && height == m_layoutHeight;
        if ( layoutDone ) {
            // nothing to do
        } else if ( null == m_gi ) {
            // nothing to do either
        } else if ( null == m_jniThread ) {
            // nothing to do either
        } else if ( null == m_dims ) {
            // m_canvas = null;
            // need to synchronize??
            Paint paint = new Paint();
            paint.setTextSize( m_mediumFontHt );
            Rect scratch = new Rect();
            String timerTxt = "-00:00";
            paint.getTextBounds( timerTxt, 0, timerTxt.length(), scratch );
            int timerWidth = scratch.width();
            int fontWidth = 
                Math.min(m_defaultFontHt, timerWidth / timerTxt.length());
            m_jniThread.handle( JNIThread.JNICmd.CMD_LAYOUT, width, height, 
                                fontWidth, m_defaultFontHt );
            // We'll be back....
        } else {
            // If board size has changed we need a new bitmap
            int bmHeight = 1 + m_dims.height;
            int bmWidth = 1 + m_dims.width;
            if ( null != s_bitmap ) {
                if ( s_bitmap.getHeight() != bmHeight
                     || s_bitmap.getWidth() != bmWidth ) {
                    s_bitmap = null;
                    m_canvas = null;
                }
            }

            if ( null == s_bitmap ) {
                s_bitmap = Bitmap.createBitmap( bmWidth, bmHeight,
                                                Bitmap.Config.ARGB_8888 );
            }
            if ( null == m_canvas ) {
                m_canvas = new BoardCanvas( m_parent, s_bitmap, m_jniThread, 
                                            m_dims );
            } else {
                m_canvas.setJNIThread( m_jniThread );
            }
            m_jniThread.handle( JNIThread.JNICmd.CMD_SETDRAW, m_canvas );
            m_jniThread.handle( JNIThread.JNICmd.CMD_DRAW );

            // set so we know we're done
            m_layoutWidth = width;
            m_layoutHeight = height;
            layoutDone = true;
        }
        return layoutDone;
    } // layoutBoardOnce

    // BoardHandler interface implementation
    public void startHandling( Activity parent, JNIThread thread, 
                               int gamePtr, CurGameInfo gi, 
                               CommsAddrRec.CommsConnType connType ) 
    {
        m_parent = parent;
        m_jniThread = thread;
        m_jniGamePtr = gamePtr;
        m_gi = gi;
        m_connType = connType;
        m_layoutWidth = 0;
        m_layoutHeight = 0;

        // Set the jni layout if we already have one
        if ( null != m_dims ) {
            m_jniThread.handle( JNIThread.JNICmd.CMD_LAYOUT, m_dims );
        }

        // Make sure we draw.  Sometimes when we're reloading after
        // an obsuring Activity goes away we otherwise won't.
        invalidate();
    }

    public void stopHandling()
    {
        m_jniThread = null;
        m_jniGamePtr = 0;
        if ( null != m_canvas ) {
            m_canvas.setJNIThread( null );
        }
    }

    // SyncedDraw interface implementation
    public void doJNIDraw()
    {
        boolean drew;
        synchronized( this ) {
            if ( !XwJNI.board_draw( m_jniGamePtr ) ) {
                DbgUtils.logf( "doJNIDraw: draw not complete" );
            }
        }

        // Force update now that we have bits to copy
        m_parent.runOnUiThread( m_invalidator );
    }

    public void dimsChanged( BoardDims dims )
    {
        m_dims = dims;
        m_parent.runOnUiThread( new Runnable() {
                public void run()
                {
                    requestLayout();
                }
            });
    }

    public void setInTrade( boolean inTrade ) 
    {
        if ( null != m_canvas ) {
            m_canvas.setInTrade( inTrade );
        }
    }

    public int getCurPlayer()
    {
        return null == m_canvas? -1 : m_canvas.getCurPlayer();
    }

    public int curPending() 
    {
        return null == m_canvas? 0 : m_canvas.curPending();
    }

    private int getSpacing( MotionEvent event ) 
    {
        int result;
        if ( 1 == event.getPointerCount() ) {
            result = MULTI_INACTIVE;
        } else {
            float xx = event.getX( 0 ) - event.getX( 1 );
            float yy = event.getY( 0 ) - event.getY( 1 );
            result = (int)FloatMath.sqrt( (xx * xx) + (yy * yy) );
        }
        return result;
    }

    private int figureZoom( MotionEvent event )
    {
        int zoomDir = 0;
        if ( MULTI_INACTIVE != m_lastSpacing ) {
            int newSpacing = getSpacing( event );
            int diff = Math.abs( newSpacing - m_lastSpacing );
            if ( diff > PINCH_THRESHOLD ) {
                zoomDir = newSpacing < m_lastSpacing? -1 : 1;
                m_lastSpacing = newSpacing;
            }
        }
        return zoomDir;
    }

}

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


package org.eehouse.android.xw4.jni;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import java.lang.InterruptedException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

import org.eehouse.android.xw4.ConnStatusHandler;
import org.eehouse.android.xw4.DBUtils;
import org.eehouse.android.xw4.DbgUtils;
import org.eehouse.android.xw4.GameLock;
import org.eehouse.android.xw4.GameUtils;
import org.eehouse.android.xw4.R;
import org.eehouse.android.xw4.Toolbar;
import org.eehouse.android.xw4.XWPrefs;
import org.eehouse.android.xw4.jni.CurGameInfo.DeviceRole;
import org.eehouse.android.xw4.jni.DrawCtx;
import junit.framework.Assert;

public class JNIThread extends Thread {

    public enum JNICmd { CMD_NONE,
            // CMD_RUN,
            CMD_DRAW,
            CMD_SETDRAW,
            CMD_INVALALL,
            CMD_LAYOUT,
            CMD_START,
            CMD_SWITCHCLIENT,
            CMD_RESET,
            CMD_SAVE,
            CMD_DO,
            CMD_RECEIVE,
            CMD_TRANSFAIL,
            CMD_PREFS_CHANGE,
            CMD_PEN_DOWN,
            CMD_PEN_MOVE,
            CMD_PEN_UP,
            CMD_KEYDOWN,
            CMD_KEYUP,
            CMD_TIMER_FIRED,
            CMD_COMMIT,
            CMD_JUGGLE,
            CMD_FLIP,
            CMD_TOGGLE_TRAY,
            CMD_TRADE,
            CMD_CANCELTRADE,
            CMD_UNDO_CUR,
            CMD_UNDO_LAST,
            CMD_ZOOM,
            CMD_TOGGLEZOOM,
            CMD_PREV_HINT,
            CMD_NEXT_HINT,
            CMD_VALUES,
            CMD_COUNTS_VALUES,
            CMD_REMAINING,
            CMD_RESEND,
            // CMD_ACKANY,
            CMD_HISTORY,
            CMD_FINAL,
            CMD_ENDGAME,
            CMD_POST_OVER,
            CMD_SENDCHAT,
            CMD_NETSTATS,
            // CMD_DRAW_CONNS_STATUS,
            // CMD_DRAW_BT_STATUS,
            // CMD_DRAW_SMS_STATUS,
            };

    public static final int RUNNING = 1;
    public static final int DIALOG = 2;
    public static final int QUERY_ENDGAME = 3;
    public static final int TOOLBAR_STATES = 4;
    public static final int GOT_WORDS = 5;
    public static final int GAME_OVER = 6;

    public class GameStateInfo implements Cloneable {
        public int visTileCount;
        public int trayVisState;
        public int nPendingMessages;
        public boolean canHint;
        public boolean canUndo;
        public boolean canRedo;
        public boolean inTrade;
        public boolean tradeTilesSelected;
        public boolean canChat;
        public boolean canShuffle;
        public boolean curTurnSelected;
        public boolean canHideRack;
        public boolean canTrade;
        public GameStateInfo clone() {
            GameStateInfo obj = null;
            try {
                obj = (GameStateInfo)super.clone();
            } catch ( CloneNotSupportedException cnse ) {
            }
            return obj;
        }
    }

    private GameStateInfo m_gsi = new GameStateInfo();

    private boolean m_stopped = false;
    private boolean m_saveOnStop = false;
    private int m_jniGamePtr;
    private byte[] m_gameAtStart;
    private GameLock m_lock;
    private Context m_context;
    private CurGameInfo m_gi;
    private Handler m_handler;
    private SyncedDraw m_drawer;
    private static final int kMinDivWidth = 10;
    private int m_connsIconID = 0;
    private String m_newDict = null;

    LinkedBlockingQueue<QueueElem> m_queue;

    private class QueueElem {
        protected QueueElem( JNICmd cmd, boolean isUI, Object[] args )
        {
            m_cmd = cmd; m_isUIEvent = isUI; m_args = args;
        }
        boolean m_isUIEvent;
        JNICmd m_cmd;
        Object[] m_args;
    }

    public JNIThread( int gamePtr, byte[] gameAtStart, CurGameInfo gi, 
                      SyncedDraw drawer, GameLock lock, Context context, 
                      Handler handler ) 
    {
        m_jniGamePtr = gamePtr;
        m_gameAtStart = gameAtStart;
        m_gi = gi;
        m_drawer = drawer;
        m_lock = lock;
        m_context = context;
        m_handler = handler;

        m_queue = new LinkedBlockingQueue<QueueElem>();
    }

    public void waitToStop( boolean save )
    {
        synchronized ( this ) {
            m_stopped = true;
            m_saveOnStop = save;
        }
        handle( JNICmd.CMD_NONE );     // tickle it
        try {
            // Can't pass timeout to join.  There's no way to kill
            // this thread unless it's doing something interruptable
            // (like blocking on a socket) so might as well let it
            // take however log it takes.  If that's too long, fix it.
            join();
            // Assert.assertFalse( isAlive() );
        } catch ( java.lang.InterruptedException ie ) {
            DbgUtils.loge( ie );
        }
    }

    public boolean busy()
    {                           // synchronize this!!!
        boolean result = false;
        Iterator<QueueElem> iter = m_queue.iterator();
        while ( iter.hasNext() ) {
            if ( iter.next().m_isUIEvent ) {
                result = true;
                break;
            }
        }
        return result;
    }

    public GameStateInfo getGameStateInfo()
    {
        synchronized( m_gsi ) {
            return m_gsi.clone();
        }
    }

    // Gross hack.  This is the easiest way to set the dict without
    // rewriting game loading code or running into cross-threading
    // issues.
    public void setSaveDict( String newDict )
    {
        m_newDict = newDict;
    }

    private boolean toggleTray() {
        boolean draw;
        int state = XwJNI.board_getTrayVisState( m_jniGamePtr );
        if ( state == XwJNI.TRAY_REVEALED ) {
            draw = XwJNI.board_hideTray( m_jniGamePtr );
        } else {
            draw = XwJNI.board_showTray( m_jniGamePtr );
        }
        return draw;
    }

    private void sendForDialog( int titleArg, String text )
    {
        Message.obtain( m_handler, DIALOG, titleArg, 0, text ).sendToTarget();
    }

    private void doLayout( int width, int height, int fontWidth,
                           int fontHeight )
    {
        BoardDims dims = new BoardDims();

        boolean squareTiles = XWPrefs.getSquareTiles( m_context );
        XwJNI.board_figureLayout( m_jniGamePtr, m_gi, 0, 0, width, height,
                                  150 /*scorePct*/, 200 /*trayPct*/, 
                                  width, fontWidth, fontHeight, squareTiles, 
                                  dims /* out param */ );
        int statusWidth = dims.boardWidth / 15;
        dims.scoreWidth -= statusWidth;
        int left = dims.scoreLeft + dims.scoreWidth + dims.timerWidth;
        ConnStatusHandler.setRect( left, dims.top, left + statusWidth, 
                                   dims.top + dims.scoreHt );

        XwJNI.board_applyLayout( m_jniGamePtr, dims );

        m_drawer.dimsChanged( dims );
    }

    private boolean nextSame( JNICmd cmd ) 
    {
        QueueElem nextElem = m_queue.peek();
        return null != nextElem && nextElem.m_cmd == cmd;
    }

    private boolean processKeyEvent( JNICmd cmd, XwJNI.XP_Key xpKey,
                                     boolean[] barr )
    {
        boolean draw = false;
        return draw;
    } // processKeyEvent

    private void checkButtons()
    {
        synchronized( m_gsi ) {
            XwJNI.game_getState( m_jniGamePtr, m_gsi );
        }
        Message.obtain( m_handler, TOOLBAR_STATES ).sendToTarget();
    }

    private void save_jni()
    {
        // If server has any work to do, e.g. clean up after showing a
        // remote- or robot-moved dialog, let it do so before saving
        // state.  In some cases it'll otherwise drop the move.
        XwJNI.server_do( m_jniGamePtr );

        // And let it tell the relay (if any) it's leaving
        // XwJNI.comms_stop( m_jniGamePtr );

        XwJNI.game_getGi( m_jniGamePtr, m_gi );
        if ( null != m_newDict ) {
            m_gi.dictName = m_newDict;
        }
        byte[] state = XwJNI.game_saveToStream( m_jniGamePtr, m_gi );
        if ( Arrays.equals( m_gameAtStart, state ) ) {
            // DbgUtils.logf( "no change in game; can skip saving" );
        } else {
            synchronized( this ) {
                Assert.assertNotNull( m_lock );
                GameSummary summary = new GameSummary( m_context, m_gi );
                XwJNI.game_summarize( m_jniGamePtr, summary );
                DBUtils.saveGame( m_context, m_lock, state, false );
                DBUtils.saveSummary( m_context, m_lock, summary );
                // There'd better be no way for saveGame above to fail!
                XwJNI.game_saveSucceeded( m_jniGamePtr );
            }
        }
    }

    @SuppressWarnings("fallthrough")
    public void run() 
    {
        boolean[] barr = new boolean[2]; // scratch boolean
        for ( ; ; ) {
            synchronized ( this ) {
                if ( m_stopped ) {
                    break;
                }
            }

            final QueueElem elem;
            Object[] args;
            try {
                elem = m_queue.take();
            } catch ( InterruptedException ie ) {
                DbgUtils.logf( "interrupted; killing thread" );
                break;
            }
            boolean draw = false;
            args = elem.m_args;
            switch( elem.m_cmd ) {

            case CMD_SAVE:
                if ( nextSame( JNICmd.CMD_SAVE ) ) {
                    continue;
                }
                save_jni();
                // This is gross: we take the relay connection down
                // then bring it right back up again each time there's
                // a message received (to save any state changes it
                // brought).  There must be a better way.
                // XwJNI.comms_start( m_jniGamePtr );
                break;

            case CMD_DRAW:
                if ( nextSame( JNICmd.CMD_DRAW ) ) {
                    continue;
                }
                draw = true;
                break;

            case CMD_SETDRAW:
                XwJNI.board_setDraw( m_jniGamePtr, (DrawCtx)args[0] );
                XwJNI.board_invalAll( m_jniGamePtr );
                break;

            case CMD_INVALALL:
                XwJNI.board_invalAll( m_jniGamePtr );
                draw = true;
                break;

            case CMD_LAYOUT:
                Object args0 = args[0];
                BoardDims dims = null;
                if ( args0 instanceof BoardDims ) {
                    dims = (BoardDims)args0;
                    XwJNI.board_applyLayout( m_jniGamePtr, dims );
                } else {
                    doLayout( (Integer)args0, (Integer)args[1], 
                              (Integer)args[2], (Integer)args[3] );
                }
                draw = true;
                // check and disable zoom button at limit
                handle( JNICmd.CMD_ZOOM, 0 );
                break;

            case CMD_RESET:
                XwJNI.comms_resetSame( m_jniGamePtr );
                // FALLTHRU
            case CMD_START:
                XwJNI.comms_start( m_jniGamePtr );
                if ( m_gi.serverRole == DeviceRole.SERVER_ISCLIENT ) {
                    XwJNI.server_initClientConnection( m_jniGamePtr );
                }
                draw = XwJNI.server_do( m_jniGamePtr );
                break;

            case CMD_SWITCHCLIENT:
                XwJNI.server_reset( m_jniGamePtr );
                XwJNI.server_initClientConnection( m_jniGamePtr );
                draw = XwJNI.server_do( m_jniGamePtr );
                break;

            case CMD_DO:
                if ( nextSame( JNICmd.CMD_DO ) ) {
                    continue;
                }
                draw = XwJNI.server_do( m_jniGamePtr );
                break;

            case CMD_RECEIVE:
                draw = XwJNI.game_receiveMessage( m_jniGamePtr, 
                                                  (byte[])args[0],
                                                  (CommsAddrRec)args[1]);
                handle( JNICmd.CMD_DO );
                if ( draw ) {
                    handle( JNICmd.CMD_SAVE );
                }
                break;

            case CMD_TRANSFAIL:
                XwJNI.comms_transportFailed( m_jniGamePtr );
                break;

            case CMD_PREFS_CHANGE:
                // need to inval all because some of prefs,
                // e.g. colors, aren't known by common code so
                // board_prefsChanged's return value isn't enough.
                XwJNI.board_invalAll( m_jniGamePtr );
                XwJNI.board_server_prefsChanged( m_jniGamePtr, 
                                                 CommonPrefs.get( m_context ) );
                draw = true;
                break;

            case CMD_PEN_DOWN:
                draw = XwJNI.board_handlePenDown( m_jniGamePtr, 
                                                  ((Integer)args[0]).intValue(),
                                                  ((Integer)args[1]).intValue(),
                                                  barr );
                break;
            case CMD_PEN_MOVE:
                if ( nextSame( JNICmd.CMD_PEN_MOVE ) ) {
                    continue;
                }
                draw = XwJNI.board_handlePenMove( m_jniGamePtr, 
                                                  ((Integer)args[0]).intValue(),
                                                  ((Integer)args[1]).intValue() );
                break;
            case CMD_PEN_UP:
                draw = XwJNI.board_handlePenUp( m_jniGamePtr, 
                                                ((Integer)args[0]).intValue(),
                                                ((Integer)args[1]).intValue() );
                break;

            case CMD_KEYDOWN:
            case CMD_KEYUP:
                draw = processKeyEvent( elem.m_cmd, (XwJNI.XP_Key)args[0], barr );
                break;

            case CMD_COMMIT:
                draw = XwJNI.board_commitTurn( m_jniGamePtr );
                break;

            case CMD_JUGGLE:
                draw = XwJNI.board_juggleTray( m_jniGamePtr );
                break;
            case CMD_FLIP:
                draw = XwJNI.board_flip( m_jniGamePtr );
                break;
            case CMD_TOGGLE_TRAY:
                draw = toggleTray();
                break;
            case CMD_TRADE:
                draw = XwJNI.board_beginTrade( m_jniGamePtr );
                break;
            case CMD_CANCELTRADE:
                draw = XwJNI.board_endTrade( m_jniGamePtr );
                break;
            case CMD_UNDO_CUR:
                draw = XwJNI.board_replaceTiles( m_jniGamePtr )
                    || XwJNI.board_redoReplacedTiles( m_jniGamePtr );
                break;
            case CMD_UNDO_LAST:
                XwJNI.server_handleUndo( m_jniGamePtr );
                draw = true;
                break;

            case CMD_NEXT_HINT:
            case CMD_PREV_HINT:
                if ( nextSame( elem.m_cmd ) ) {
                    continue;
                }
                draw = XwJNI.board_requestHint( m_jniGamePtr, false, 
                                                JNICmd.CMD_PREV_HINT==elem.m_cmd,
                                                barr );
                if ( barr[0] ) {
                    handle( elem.m_cmd );
                    draw = false;
                }
                break;

            case CMD_TOGGLEZOOM:
                XwJNI.board_zoom( m_jniGamePtr, 0 , barr );
                int zoomBy = 0;
                if ( barr[1] ) { // always go out if possible
                    zoomBy = -5;
                } else if ( barr[0] ) {
                    zoomBy = 5;
                }
                draw = XwJNI.board_zoom( m_jniGamePtr, zoomBy, barr );
                break;
            case CMD_ZOOM:
                draw = XwJNI.board_zoom( m_jniGamePtr, 
                                         ((Integer)args[0]).intValue(),
                                         barr );
                break;

            case CMD_VALUES:
                draw = XwJNI.board_toggle_showValues( m_jniGamePtr );
                break;

            case CMD_COUNTS_VALUES:
                sendForDialog( ((Integer)args[0]).intValue(),
                               XwJNI.server_formatDictCounts( m_jniGamePtr, 3 )
                               );
                break;
            case CMD_REMAINING:
                sendForDialog( ((Integer)args[0]).intValue(),
                               XwJNI.board_formatRemainingTiles( m_jniGamePtr )
                               );
                break;

            case CMD_RESEND:
                XwJNI.comms_resendAll( m_jniGamePtr, 
                                       ((Boolean)args[0]).booleanValue(),
                                       ((Boolean)args[1]).booleanValue() );
                break;
            // case CMD_ACKANY:
            //     XwJNI.comms_ackAny( m_jniGamePtr );
            //     break;

            case CMD_HISTORY:
                boolean gameOver = XwJNI.server_getGameIsOver( m_jniGamePtr );
                sendForDialog( ((Integer)args[0]).intValue(),
                               XwJNI.model_writeGameHistory( m_jniGamePtr, 
                                                             gameOver ) );
                break;

            case CMD_FINAL:
                if ( XwJNI.server_getGameIsOver( m_jniGamePtr ) ) {
                    handle( JNICmd.CMD_POST_OVER );
                } else {
                    Message.obtain( m_handler, QUERY_ENDGAME ).sendToTarget();
                }
                break;

            case CMD_ENDGAME:
                XwJNI.server_endGame( m_jniGamePtr );
                draw = true;
                break;

            case CMD_POST_OVER:
                if ( XwJNI.server_getGameIsOver( m_jniGamePtr ) ) {
                    boolean auto = 0 < args.length &&
                        ((Boolean)args[0]).booleanValue();
                    int titleID = auto? R.string.summary_gameover
                        : R.string.finalscores_title;
                    
                    String text = XwJNI.server_writeFinalScores( m_jniGamePtr );
                    Message.obtain( m_handler, GAME_OVER, titleID, 0, text )
                        .sendToTarget();
                }
                break;

            case CMD_SENDCHAT:
                XwJNI.server_sendChat( m_jniGamePtr, (String)args[0] );
                break;

            case CMD_NETSTATS:
                sendForDialog( ((Integer)args[0]).intValue(),
                               XwJNI.comms_getStats( m_jniGamePtr )
                               );
                break;

            case CMD_TIMER_FIRED:
                draw = XwJNI.timerFired( m_jniGamePtr, 
                                         ((Integer)args[0]).intValue(),
                                         ((Integer)args[1]).intValue(),
                                         ((Integer)args[2]).intValue() );
                break;

            case CMD_NONE:      // ignored
                break;
            default:
                DbgUtils.logf( "dropping cmd: %s", elem.m_cmd.toString() );
                Assert.fail();
            }

            if ( draw ) {
                // do the drawing in this thread but in BoardView
                // where it can be synchronized with that class's use
                // of the same bitmap for blitting.
                m_drawer.doJNIDraw();

                checkButtons();
            }
        } // for

        if ( m_saveOnStop ) {
            XwJNI.comms_stop( m_jniGamePtr );
            save_jni();
        } else {
            DbgUtils.logf( "JNIThread.run(): exiting without saving" );
        }
    } // run

    public void handleBkgrnd( JNICmd cmd, Object... args )
    {
        QueueElem elem = new QueueElem( cmd, false, args );
        // DbgUtils.logf( "adding: %s", cmd.toString() );
        m_queue.add( elem );
    }

    public void handle( JNICmd cmd, Object... args )
    {
        QueueElem elem = new QueueElem( cmd, true, args );
        m_queue.add( elem );
        if ( m_stopped && ! JNICmd.CMD_NONE.equals(cmd) ) {
            DbgUtils.logf( "WARNING: adding %s to stopped thread!!!", 
                           cmd.toString() );
            DbgUtils.printStack();
        }
    }

    // public void run( boolean isUI, Runnable runnable )
    // {
    //     Object[] args = { runnable };
    //     QueueElem elem = new QueueElem( JNICmd.CMD_RUN, isUI, args );
    //     m_queue.add( elem );
    // }
}

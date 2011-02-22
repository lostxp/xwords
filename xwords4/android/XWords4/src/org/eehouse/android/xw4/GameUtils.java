/* -*- compile-command: "cd ../../../../../; ant install"; -*- */
/*
 * Copyright 2009-2010 by Eric House (xwords@eehouse.org).  All
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
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.net.Uri;
import java.util.ArrayList;
import android.content.res.AssetManager;
import junit.framework.Assert;

import org.eehouse.android.xw4.jni.*;
import org.eehouse.android.xw4.jni.CurGameInfo.DeviceRole;

public class GameUtils {

    private static Object s_syncObj = new Object();

    public static byte[] savedGame( Context context, String path )
    {
        return DBUtils.loadGame( context, path );
    } // savedGame

    /**
     * Open an existing game, and use its gi and comms addr as the
     * basis for a new one.
     */
    public static void resetGame( Context context, String pathIn, 
                                  String pathOut )
    {
        int gamePtr = XwJNI.initJNI();
        CurGameInfo gi = new CurGameInfo( context );
        CommsAddrRec addr = null;

        // loadMakeGame, if makinga new game, will add comms as long
        // as DeviceRole.SERVER_STANDALONE != gi.serverRole
        loadMakeGame( context, gamePtr, gi, pathIn );
        byte[] dictBytes = GameUtils.openDict( context, gi.dictName );
        
        if ( XwJNI.game_hasComms( gamePtr ) ) {
            addr = new CommsAddrRec( context );
            XwJNI.comms_getAddr( gamePtr, addr );
            if ( CommsAddrRec.CommsConnType.COMMS_CONN_NONE == addr.conType ) {
                String relayName = CommonPrefs.getDefaultRelayHost( context );
                int relayPort = CommonPrefs.getDefaultRelayPort( context );
                XwJNI.comms_getInitialAddr( addr, relayName, relayPort );
            }
        }
        XwJNI.game_dispose( gamePtr );

        gi.setInProgress( false );

        gamePtr = XwJNI.initJNI();
        XwJNI.game_makeNewGame( gamePtr, gi, JNIUtilsImpl.get(), 
                                CommonPrefs.get( context ), dictBytes, 
                                gi.dictName );
        if ( null != addr ) {
            XwJNI.comms_setAddr( gamePtr, addr );
        }

        saveGame( context, gamePtr, gi, pathOut, true );
        summarizeAndClose( context, pathOut, gamePtr, gi );
    } // resetGame

    public static void resetGame( Context context, String pathIn )
    {
        tellRelayDied( context, pathIn, true );
        resetGame( context, pathIn, pathIn );
    }

    private static GameSummary summarizeAndClose( Context context, 
                                                  String path,
                                                  int gamePtr, CurGameInfo gi )
    {
        return summarizeAndClose( context, path, gamePtr, gi, null );
    }

    private static GameSummary summarizeAndClose( Context context, 
                                                  String path,
                                                  int gamePtr, CurGameInfo gi,
                                                  FeedUtilsImpl feedImpl )
    {
        GameSummary summary = new GameSummary( gi );
        XwJNI.game_summarize( gamePtr, summary );

        if ( null != feedImpl ) {
            if ( feedImpl.m_gotChat ) {
                summary.pendingMsgLevel = GameSummary.MsgLevel.MSG_LEVEL_CHAT;
            } else if ( feedImpl.m_gotMsg ) {
                summary.pendingMsgLevel = GameSummary.MsgLevel.MSG_LEVEL_TURN;
            }
        }

        DBUtils.saveSummary( context, path, summary );

        XwJNI.game_dispose( gamePtr );
        return summary;
    }

    public static GameSummary summarize( Context context, String path )
    {
        int gamePtr = XwJNI.initJNI();
        CurGameInfo gi = new CurGameInfo( context );
        loadMakeGame( context, gamePtr, gi, path );

        return summarizeAndClose( context, path, gamePtr, gi );
    }

    public static String dupeGame( Context context, String pathIn )
    {
        String newName = newName( context );
        resetGame( context, pathIn, newName );
        return newName;
    }

    public static void deleteGame( Context context, String path,
                                   boolean informNow )
    {
        // does this need to be synchronized?
        tellRelayDied( context, path, informNow );
        DBUtils.deleteGame( context, path );
    }

    public static void loadMakeGame( Context context, int gamePtr, 
                                     CurGameInfo gi, String path )
    {
        loadMakeGame( context, gamePtr, gi, null, path );
    }

    public static void loadMakeGame( Context context, int gamePtr, 
                                     CurGameInfo gi, UtilCtxt util,
                                     String path )
    {
        byte[] stream = savedGame( context, path );
        XwJNI.gi_from_stream( gi, stream );
        byte[] dictBytes = GameUtils.openDict( context, gi.dictName );

        boolean madeGame = XwJNI.game_makeFromStream( gamePtr, stream, 
                                                      JNIUtilsImpl.get(), gi, 
                                                      dictBytes, gi.dictName,
                                                      util, 
                                                      CommonPrefs.get(context));
        if ( !madeGame ) {
            XwJNI.game_makeNewGame( gamePtr, gi, JNIUtilsImpl.get(), 
                                    CommonPrefs.get(context), dictBytes, 
                                    gi.dictName );
        }
    }

    public static void saveGame( Context context, int gamePtr, 
                                 CurGameInfo gi, String path, 
                                 boolean setCreate )
    {
        byte[] stream = XwJNI.game_saveToStream( gamePtr, gi );
        saveGame( context, stream, path, setCreate );
    }

    public static void saveGame( Context context, int gamePtr, 
                                 CurGameInfo gi )
    {
        saveGame( context, gamePtr, gi, newName( context ), false );
    }

    public static void saveGame( Context context, byte[] bytes, 
                                 String path, boolean setCreate )
    {
        DBUtils.saveGame( context, path, bytes, setCreate );
    }

    public static String saveGame( Context context, byte[] bytes )
    {
        String name = newName( context );
        saveGame( context, bytes, name, false );
        return name;
    }

    public static boolean gameDictHere( Context context, String path )
    {
        return gameDictHere( context, path, null, null );
    }

    public static boolean gameDictHere( Context context, String path, 
                                        String[] missingName, 
                                        int[] missingLang )
    {
        byte[] stream = savedGame( context, path );
        CurGameInfo gi = new CurGameInfo( context );
        XwJNI.gi_from_stream( gi, stream );
        String dictName = removeDictExtn( gi.dictName );
        if ( null != missingName ) {
            missingName[0] = dictName;
        }
        if ( null != missingLang ) {
            missingLang[0] = gi.dictLang;
        }

        boolean exists = false;
        for ( String name : dictList( context ) ) {
            if ( name.equals( dictName ) ){
                exists = true;
                break;
            }
        }
        return exists;
    }

    public static boolean gameDictHere( Context context, int indx, 
                                        String[] name, int[] lang )
    {
        String path = DBUtils.gamesList( context )[indx];
        return gameDictHere( context, path, name, lang );
    }

    public static String newName( Context context ) 
    {
        String name = null;
        Integer num = 1;
        int ii;
        String[] files = DBUtils.gamesList( context );
        String fmt = context.getString( R.string.gamef );

        while ( name == null ) {
            name = String.format( fmt + XWConstants.GAME_EXTN, num );
            for ( ii = 0; ii < files.length; ++ii ) {
                if ( files[ii].equals(name) ) {
                    ++num;
                    name = null;
                }
            }
        }
        return name;
    }

    public static String[] dictList( Context context )
    {
        ArrayList<String> al = new ArrayList<String>();

        for ( String file : getAssets( context ) ) {
            if ( isDict( file ) ) {
                al.add( removeDictExtn( file ) );
            }
        }

        for ( String file : context.fileList() ) {
            if ( isDict( file ) ) {
                al.add( removeDictExtn( file ) );
            }
        }

        return al.toArray( new String[al.size()] );
    }

    public static boolean dictExists( Context context, String name )
    {
        boolean exists = dictIsBuiltin( context, name );
        if ( !exists ) {
            name = addDictExtn( name );
            try {
                FileInputStream fis = context.openFileInput( name );
                fis.close();
                exists = true;
            } catch ( java.io.FileNotFoundException fnf ) {
            } catch ( java.io.IOException ioe ) {
            }
        }
        return exists;
    }

    public static boolean dictIsBuiltin( Context context, String name )
    {
        boolean builtin = false;
        name = addDictExtn( name );

        for ( String file : getAssets( context ) ) {
            if ( file.equals( name ) ) {
                builtin = true;
                break;
            }
        }

        return builtin;
    }

    public static void deleteDict( Context context, String name )
    {
        context.deleteFile( addDictExtn( name ) );
    }

    public static byte[] openDict( Context context, String name )
    {
        byte[] bytes = null;

        name = addDictExtn( name );

        try {
            AssetManager am = context.getAssets();
            InputStream dict = am.open( name, 
                            android.content.res.AssetManager.ACCESS_RANDOM );

            int len = dict.available(); // this may not be the full length!
            bytes = new byte[len];
            int nRead = dict.read( bytes, 0, len );
            if ( nRead != len ) {
                Utils.logf( "**** warning ****; read only " + nRead + " of " 
                            + len + " bytes." );
            }
            // check that with len bytes we've read the whole file
            Assert.assertTrue( -1 == dict.read() );
        } catch ( java.io.IOException ee ){
            Utils.logf( "%s failed to open; likely not built-in", name );
        }

        // not an asset?  Try storage
        if ( null == bytes ) {
            try {
                FileInputStream fis = context.openFileInput( name );
                int len = (int)fis.getChannel().size();
                bytes = new byte[len];
                fis.read( bytes, 0, len );
                fis.close();
            } catch ( java.io.FileNotFoundException fnf ) {
                Utils.logf( fnf.toString() );
            } catch ( java.io.IOException ioe ) {
                Utils.logf( ioe.toString() );
            }
        }
        
        return bytes;
    }

    public static void saveDict( Context context, String name, InputStream in )
    {
        try {
            FileOutputStream fos = context.openFileOutput( name,
                                                           Context.MODE_PRIVATE );
            byte[] buf = new byte[1024];
            int nRead;
            while( 0 <= (nRead = in.read( buf, 0, buf.length )) ) {
                fos.write( buf, 0, nRead );
            }
            fos.close();
        } catch ( java.io.FileNotFoundException fnf ) {
            Utils.logf( "saveDict: FileNotFoundException: %s", fnf.toString() );
        } catch ( java.io.IOException ioe ) {
            Utils.logf( "saveDict: IOException: %s", ioe.toString() );
            deleteDict( context, name );
        }
    } 

    private static boolean isGame( String file )
    {
        return file.endsWith( XWConstants.GAME_EXTN );
    }

    private static boolean isDict( String file )
    {
        return file.endsWith( XWConstants.DICT_EXTN );
    }

    public static String gameName( Context context, String path )
    {
        return path.substring( 0, path.lastIndexOf( XWConstants.GAME_EXTN ) );
    }

    public static void launchGame( Activity activity, String path )
    {
        File file = new File( path );
        Uri uri = Uri.fromFile( file );
        Intent intent = new Intent( Intent.ACTION_EDIT, uri,
                                    activity, BoardActivity.class );
        activity.startActivity( intent );
    }

    public static void launchGameAndFinish( Activity activity, String path )
    {
        launchGame( activity, path );
        activity.finish();
    }

    private static class FeedUtilsImpl extends UtilCtxtImpl {
        private Context m_context;
        private String m_path;
        public boolean m_gotMsg;
        public boolean m_gotChat;

        public FeedUtilsImpl( Context context, String path )
        {
            m_context = context;
            m_path = path;
            m_gotMsg = false;
        }
        public void showChat( String msg )
        {
            DBUtils.appendChatHistory( m_context, m_path, msg, false );
            m_gotChat = true;
        }
        public void turnChanged()
        {
            m_gotMsg = true;
        }
    }

    public static boolean feedMessages( Context context, String relayID,
                                        byte[][] msgs )
    {
        boolean draw = false;
        String path = DBUtils.getPathFor( context, relayID );
        if ( null != path ) {
            int gamePtr = XwJNI.initJNI();
            CurGameInfo gi = new CurGameInfo( context );
            FeedUtilsImpl feedImpl = new FeedUtilsImpl( context, path );
            loadMakeGame( context, gamePtr, gi, feedImpl, path );

            for ( byte[] msg : msgs ) {
                draw = XwJNI.game_receiveMessage( gamePtr, msg ) || draw;
            }

            // update gi to reflect changes due to messages
            XwJNI.game_getGi( gamePtr, gi );
            saveGame( context, gamePtr, gi, path, false );
            summarizeAndClose( context, path, gamePtr, gi, feedImpl );
            if ( feedImpl.m_gotChat ) {
                DBUtils.setHasMsgs( path, GameSummary.MsgLevel.MSG_LEVEL_CHAT );
                draw = true;
            } else if ( feedImpl.m_gotMsg ) {
                DBUtils.setHasMsgs( path, GameSummary.MsgLevel.MSG_LEVEL_TURN );
                draw = true;
            }
        }
        Utils.logf( "feedMessages=>%s", draw?"true":"false" );
        return draw;
    }

    // This *must* involve a reset if the language is changing!!!
    // Which isn't possible right now, so make sure the old and new
    // dict have the same langauge code.
    public static void replaceDict( Context context, String path,
                                    String dict )
    {
        byte[] stream = savedGame( context, path );
        CurGameInfo gi = new CurGameInfo( context );
        byte[] dictBytes = GameUtils.openDict( context, dict );

        int gamePtr = XwJNI.initJNI();
        XwJNI.game_makeFromStream( gamePtr, stream, 
                                   JNIUtilsImpl.get(), gi,
                                   dictBytes, dict,         
                                   CommonPrefs.get( context ) );
        gi.dictName = dict;

        saveGame( context, gamePtr, gi, path, false );

        summarizeAndClose( context, path, gamePtr, gi );
    }

    public static void applyChanges( Context context, CurGameInfo gi, 
                                     CommsAddrRec car, String path, 
                                     boolean forceNew )
    {
        // This should be a separate function, commitChanges() or
        // somesuch.  But: do we have a way to save changes to a gi
        // that don't reset the game, e.g. player name for standalone
        // games?
        byte[] dictBytes = GameUtils.openDict( context, gi.dictName );
        int gamePtr = XwJNI.initJNI();
        boolean madeGame = false;
        CommonPrefs cp = CommonPrefs.get( context );

        if ( forceNew ) {
            tellRelayDied( context, path, true );
        } else {
            byte[] stream = GameUtils.savedGame( context, path );
            // Will fail if there's nothing in the stream but a gi.
            madeGame = XwJNI.game_makeFromStream( gamePtr, stream, 
                                                  JNIUtilsImpl.get(),
                                                  new CurGameInfo(context), 
                                                  dictBytes, gi.dictName, cp );
        }

        if ( forceNew || !madeGame ) {
            gi.setInProgress( false );
            XwJNI.game_makeNewGame( gamePtr, gi, JNIUtilsImpl.get(), 
                                    cp, dictBytes, gi.dictName );
        }

        if ( null != car ) {
            XwJNI.comms_setAddr( gamePtr, car );
        }

        saveGame( context, gamePtr, gi, path, false );

        GameSummary summary = new GameSummary( gi );
        XwJNI.game_summarize( gamePtr, summary );
        DBUtils.saveSummary( context, path, summary );

        XwJNI.game_dispose( gamePtr );
    } // applyChanges

    public static void doConfig( Activity activity, String path, Class clazz )
    {
        Uri uri = Uri.fromFile( new File(path) );
        Intent intent = new Intent( Intent.ACTION_EDIT, uri, activity, clazz );
        activity.startActivity( intent );
    }

    public static String removeDictExtn( String str )
    {
        if ( str.endsWith( XWConstants.DICT_EXTN ) ) {
            int indx = str.lastIndexOf( XWConstants.DICT_EXTN );
            str = str.substring( 0, indx );
        }
        return str;
    }

    private static String addDictExtn( String str ) 
    {
        if ( ! str.endsWith( XWConstants.DICT_EXTN ) ) {
            str += XWConstants.DICT_EXTN;
        }
        return str;
    }

    private static String[] getAssets( Context context )
    {
        try {
            AssetManager am = context.getAssets();
            return am.list("");
        } catch( java.io.IOException ioe ) {
            Utils.logf( ioe.toString() );
            return new String[0];
        }
    }
    
    private static void tellRelayDied( Context context, String path,
                                       boolean informNow )
    {
        GameSummary summary = DBUtils.getSummary( context, path );
        if ( null != summary.relayID ) {
            DBUtils.addDeceased( context, summary.relayID, summary.seed );
            if ( informNow ) {
                NetUtils.informOfDeaths( context );
            }
        }
    }

}

/*
 * Copyright (C) Bernhard Seybold. All rights reserved.
 *
 * This software is published under the terms of the LGPL Software License,
 * a copy of which has been included with this distribution in the LICENSE.txt
 * file.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *
 * $Id: PGNReader.java,v 1.2 2003/01/04 16:13:22 BerniMan Exp $
 */
// TODO: support empty variations
package chesspresso.pgn;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import chesspresso.Chess;
import chesspresso.game.Game;
import chesspresso.move.IllegalMoveException;
import chesspresso.move.Move;
import chesspresso.position.NAG;

/**
 * Reader for PGN files.
 *
 * @author Bernhard Seybold
 * @version $Revision: 1.2 $
 */
public final class PGNReader extends PGN {

    public static boolean isPGNFile(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pgn");
    }

    public static boolean isPGNFileOrZipped(String filename) {
        if (filename != null) {
            filename = filename.toLowerCase();
            return filename.endsWith(".pgn") || filename.endsWith(".pgn.gz")
                    || filename.endsWith(".zip");
        } else {
            return false;
        }
    }

    public static FileFilter getFileFilter() {
        return new FileFilter() {
            @Override
			public boolean accept(File file) {
                return file.isDirectory()
                        || PGNReader.isPGNFileOrZipped(file.getName());
            }

            public String getDescription() {
                return "PGN files (*.pgn, *.pgn.gz, *.zip)";
            }
        };
    }

    // ======================================================================

    private static final boolean DEBUG = false;

    private static final int MAX_TOKEN_SIZE = 16384;

    private static boolean[] s_isToken;

    static {
        s_isToken = new boolean[128];
        for (int i = 0; i < s_isToken.length; i++)
            s_isToken[i] = false;

        for (int i = 0; i <= 32; i++)
            s_isToken[i] = true;

        s_isToken[TOK_ASTERISK] = true;
        s_isToken[TOK_COMMENT_BEGIN] = true;
        s_isToken[TOK_COMMENT_END] = true;
        s_isToken[TOK_LBRACKET] = true;
        s_isToken[TOK_RBRACKET] = true;
        s_isToken[TOK_LINE_BEGIN] = true;
        s_isToken[TOK_LINE_END] = true;
        s_isToken[TOK_NAG_BEGIN] = true;
        s_isToken[TOK_PERIOD] = true;
        s_isToken[TOK_QUOTE] = true;
        s_isToken[TOK_TAG_BEGIN] = true;
        s_isToken[TOK_TAG_END] = true;
        s_isToken['!'] = true; // direct NAGs
        s_isToken['?'] = true; // direct NAGs
    }

    // ======================================================================

    private LineNumberReader m_in;
    // 1.4 private CharBuffer m_charBuf;
    private String m_filename;

    private Game m_curGame;
    private int m_lastChar;
    private int m_lastToken;
    private boolean m_pushedBack;
    private char[] m_buf;
    private int m_lastTokenLength;

    private PGNErrorHandler m_errorHandler;

    private boolean parsingMoveText;

    // ======================================================================

    public PGNReader(InputStream in, String name) {
        init();
        setInput(new InputStreamReader(in), name);
    }

    /**
     * @deprecated
     */
    @Deprecated
	public PGNReader(String filename) throws IOException {
        init();
        if (filename.toLowerCase().endsWith(".gz")) {
            setInput(new InputStreamReader(new GZIPInputStream(
                    new FileInputStream(filename))), filename);
        } else {
            setInput(new FileReader(filename), filename);
        }
    }

    /**
     * @deprecated
     */
    @Deprecated
	public PGNReader(URL url) throws MalformedURLException, IOException {
        init();
        if (url.getFile().toLowerCase().endsWith(".gz"))
            setInput(new InputStreamReader(
                    new GZIPInputStream(url.openStream())), url.getFile());
        else
            setInput(new InputStreamReader(url.openStream()), url.getFile());
    }

    /**
     * @deprecated
     */
    @Deprecated
	public PGNReader(Reader reader, String name) {
        init();
        setInput(reader, name);
    }

    // 1.4
    // public PGNReader(CharBuffer buf)
    // {
    // this();
    // m_charBuf = buf;
    // }

    private void init() {
        m_buf = new char[MAX_TOKEN_SIZE];
        m_filename = null;
        m_errorHandler = null;
        m_pushedBack = false;
        m_lastToken = TOK_EOL;
    }

    // ======================================================================

    protected void setInput(Reader reader, String name) {
        if (reader instanceof LineNumberReader) {
            m_in = (LineNumberReader) reader;
        } else {
            m_in = new LineNumberReader(reader);
        }
        m_filename = name;
    }

    public void setErrorHandler(PGNErrorHandler handler) {
        m_errorHandler = handler;
    }

    // ======================================================================

    final static int TOK_EOF = -1, TOK_EOL = -2, TOK_IDENT = -3,
            TOK_STRING = -4, TOK_NO_TOKEN = -100;

    /**
     * Returns the current line number. The first line is line 1, not line 0 as
     * LineNumberReader.
     *
     * @return the current line number
     */
    private int getLineNumber() {
        return m_in != null ? m_in.getLineNumber() + 1 : 0;
    }

    private String getLastTokenAsDebugString() {
        int last;
        last = getLastToken();
        if (last == TOK_EOF)
            return "EOF";
        if (last == TOK_EOL)
            return "EOL";
        if (last == TOK_NO_TOKEN)
            return "NO_TOKEN";
        if (last == TOK_IDENT)
            return getLastTokenAsString();
        if (last == TOK_COMMENT_BEGIN)
            return TOK_COMMENT_BEGIN + getLastTokenAsString() + TOK_COMMENT_END;
        if (last == TOK_STRING)
            return TOK_QUOTE + getLastTokenAsString() + TOK_QUOTE;
        return String.valueOf((char) last);
    }

    private void syntaxError(String msg) throws PGNSyntaxError {
        PGNSyntaxError error = new PGNSyntaxError(PGNSyntaxError.ERROR, msg,
                m_filename, getLineNumber(), getLastTokenAsDebugString());
        if (m_errorHandler != null)
            m_errorHandler.handleError(error);
        throw error;
    }

    private void warning(String msg) {
        if (m_errorHandler != null) {
            PGNSyntaxError warning = new PGNSyntaxError(PGNSyntaxError.WARNING,
                    msg, m_filename, getLineNumber(),
                    getLastTokenAsDebugString());
            m_errorHandler.handleWarning(warning);
        }
    }

    // ======================================================================

    private final int get() throws IOException {
        return m_in.read();
        // 1.4 return m_in != null ? m_in.read() : (m_charBuf.hasRemaining() ?
        // m_charBuf.get() : TOK_EOF);
    }

    private final int getChar() throws IOException {
        if (m_pushedBack) {
            m_pushedBack = false;
            return m_lastChar;
        }
        int ch = get();
        while (ch == '\n' || ch == '\r'
                || (ch == TOK_PGN_ESCAPE && m_lastToken != TOK_COMMENT_BEGIN && (parsingMoveText || m_lastToken != TOK_IDENT))
                || (ch == TOK_LINE_COMMENT && m_lastToken != TOK_COMMENT_BEGIN && (parsingMoveText || m_lastToken != TOK_IDENT))) {
            while ((ch == '\n' || ch == '\r') && ch >= 0) {
                ch = get();
            }
            if (ch == TOK_PGN_ESCAPE && m_lastToken != TOK_COMMENT_BEGIN) {
                do {
                    ch = get();
                } while (ch != '\n' && ch != '\r' && ch >= 0);
            } else if (ch == TOK_LINE_COMMENT && m_lastToken != TOK_COMMENT_BEGIN) {
                do {
                    ch = get();
                } while (ch != '\n' && ch != '\r' && ch >= 0);
            } else {
                m_pushedBack = true;
                m_lastChar = ch;
                return '\n';
            }
        }
        if (ch < 0)
            ch = TOK_EOF;
        m_lastChar = ch;
        return ch;
    }

    private int skipWhiteSpaces() throws IOException {
        int ch;
        do {
            ch = getChar();
        } while ((ch <= ' ' || ch == TOK_EOL) && ch >= 0);
        return ch;
    }

    private int getNextToken() throws PGNSyntaxError, IOException {
        m_lastTokenLength = 0;

        int ch = skipWhiteSpaces();
        if (ch == TOK_EOF) {
            m_lastToken = ch;
        } else if (ch == TOK_QUOTE) {
            for (;;) {
                ch = getChar();
                if (ch == TOK_QUOTE)
                    break;
                if (ch < 0)
                    syntaxError("Unfinished string");
                if (m_lastTokenLength >= MAX_TOKEN_SIZE)
                    syntaxError("Token too long");
                m_buf[m_lastTokenLength++] = (char) ch;
            }
            m_lastToken = TOK_STRING;
        } else if (ch == TOK_COMMENT_BEGIN) {
            m_lastToken = TOK_COMMENT_BEGIN;
            int start = getLineNumber();
            for (;;) {
                ch = getChar();
                if (ch == TOK_COMMENT_END)
                    break;
                if (ch == TOK_EOF)
                    syntaxError("Unfinished comment, started at line " + start);
                if (ch == '\n')
                    ch = ' '; // end of line -> space
                if (m_lastTokenLength >= MAX_TOKEN_SIZE)
                    syntaxError("Token too long");
                if (ch >= 0)
                    m_buf[m_lastTokenLength++] = (char) ch;
            }
        } else if (ch >= 0 && ch < s_isToken.length && s_isToken[ch]) {
            m_lastToken = ch;
        } else if (ch >= 0) {
            for (;;) {
                if (m_lastTokenLength >= MAX_TOKEN_SIZE)
                    syntaxError("Token too long");
                m_buf[m_lastTokenLength++] = (char) ch;
                ch = getChar();
                if (ch < 0)
                    break;
                if (ch < s_isToken.length && s_isToken[ch])
                    break;
            }
            m_pushedBack = true;
            m_lastToken = TOK_IDENT;
        }
        return m_lastToken;
    }

    private int getLastToken() {
        return m_lastToken;
    }

    private boolean isLastTokenIdent() {
        return m_lastToken == TOK_IDENT;
    }

    private String getLastTokenAsString() {
        return String.valueOf(m_buf, 0, m_lastTokenLength);
    }

    private boolean isLastTokenInt() {
        for (int i = 0; i < m_lastTokenLength; i++) {
            int digit = m_buf[i];
            if (digit < '0' || digit > '9')
                return false; // =====>
        }
        return true;
    }

    private int getLastTokenAsInt() throws PGNSyntaxError {
        int value = 0;
        for (int i = 0; i < m_lastTokenLength; i++) {
            int digit = m_buf[i];
            if (digit < '0' || digit > '9')
                syntaxError("Not a digit " + digit);
            value = 10 * value + (m_buf[i] - 48);
        }
        return value;
    }

    // ======================================================================
    // routines for parsing header sections

    private void initForHeader() {
        this.parsingMoveText = false;
    }

    private boolean findNextGameStart() throws PGNSyntaxError, IOException {
        for (;;) {
            int last = getLastToken();
            if (last == TOK_EOF)
                return false; // =====>
            if (last == TOK_TAG_BEGIN)
                return true; // =====>
            getNextToken();
        }
    }

    private boolean parseTag() throws PGNSyntaxError, IOException {
        if (getLastToken() == TOK_TAG_BEGIN) {
            String tagName = null, tagValue = null;

            if (getNextToken() == TOK_IDENT) {
                tagName = getLastTokenAsString();
            } else {
                syntaxError("Tag name expected");
            }

            if (getNextToken() != TOK_STRING) {
                syntaxError("Tag value expected");
            }
            tagValue = getLastTokenAsString();

            // compensate for quotes in tag values as produced eg by ChessBase
            while (getNextToken() != TOK_TAG_END) {
                tagValue = tagValue + " " + getLastTokenAsString();
            }

            try {
                m_curGame.setTag(tagName, tagValue.trim());
            } catch (Exception ex) {
                syntaxError(ex.getMessage());
            }

            if (getLastToken() != TOK_TAG_END) {
                syntaxError(TOK_TAG_END + " expected");
            }
            return true;
        } else {
            return false;
        }
    }

    private void parseTagPairSection() throws PGNSyntaxError, IOException {
        findNextGameStart();
        while (parseTag())
            getNextToken();
    }

    // ======================================================================
    // routines for parsing movetext sections

    private void initForMovetext() {
        this.parsingMoveText = true;
    }

    private boolean isLastTokenResult() throws PGNSyntaxError {
        return getLastTokenAsResult() != Chess.NO_RES;
    }

    private int getLastTokenAsResult() throws PGNSyntaxError {
        // System.out.println("CheckResult: " + getLastTokenAsString());
        if (getLastToken() == TOK_ASTERISK)
            return Chess.RES_NOT_FINISHED;
        if (getLastToken() == TOK_EOF || m_lastToken == TOK_COMMENT_BEGIN)
            return Chess.NO_RES;
        if (m_buf[0] == '1') {
            if (m_buf[1] == '/') {
                if (m_lastTokenLength == 7 && m_buf[2] == '2'
                        && m_buf[3] == '-' && m_buf[4] == '1'
                        && m_buf[5] == '/' && m_buf[6] == '2') {
                    return Chess.RES_DRAW;
                } else if (m_lastTokenLength == 3 && m_buf[2] == '2') {
                    return Chess.RES_DRAW;
                }
            } else if (m_lastTokenLength == 3 && m_buf[1] == '-'
                    && m_buf[2] == '0') {
                return Chess.RES_WHITE_WINS;
            }
        } else if (m_lastTokenLength == 3 && m_buf[0] == '0' && m_buf[1] == '-'
                && m_buf[2] == '1') {
            return Chess.RES_BLACK_WINS;
        }
        return Chess.NO_RES;
    }

    private boolean isLastTokenMoveNumber() {
        return isLastTokenInt();
    }

    private short getLastTokenAsMove() throws PGNSyntaxError {
        if (DEBUG)
            System.out.println("getLastTokenAsMove " + getLastTokenAsString());

        if (!isLastTokenIdent())
            syntaxError("Move expected");

        int next = 0;
        int last = m_lastTokenLength - 1;
        if (m_buf[last] == '+') {
            last--;
        } else if (m_buf[last] == '#') {
            last--;
        }

        // String s = getLastTokenAsString();
        // if (DEBUG) System.out.println("moveStr= " + s);
        short move = Move.ILLEGAL_MOVE;
        if (m_buf[0] == 'O' && m_buf[1] == '-' && m_buf[2] == 'O') {
            if (m_lastTokenLength >= 5 && m_buf[3] == '-' && m_buf[4] == 'O') {
                move = Move.getLongCastle(m_curGame.getPosition().getToPlay());
                next = 5;
            } else if (m_lastTokenLength >= 3) {
                move = Move.getShortCastle(m_curGame.getPosition().getToPlay());
                next = 3;
            } else {
                syntaxError("Illegal castle move");
            }
        } else if (m_buf[0] == '0' && m_buf[1] == '-' && m_buf[2] == '0') {
            if (m_lastTokenLength >= 5 && m_buf[3] == '-' && m_buf[4] == '0') {
                warning("Castles with zeros");
                move = Move.getLongCastle(m_curGame.getPosition().getToPlay());
                next = 5;
            } else if (m_lastTokenLength >= 3) {
                warning("Castles with zeros");
                move = Move.getShortCastle(m_curGame.getPosition().getToPlay());
                next = 3;
            } else {
                syntaxError("Illegal castle move");
            }
        } else if (m_lastTokenLength==2 && (m_buf[0] == '-' && m_buf[1] == '-') ||
                   (m_buf[0] == 'Z' && m_buf[1] == '0')) {
            // null move
            move = m_curGame.getPosition().getNullMove();
        } else if (m_lastTokenLength==1 && (m_buf[0]=='N' || m_buf[0]=='D'
                                            || m_buf[0]=='~' || m_buf[0]=='=')) {
            if (m_buf[0] == 'N') {
                // novelty
                m_curGame.addNag((short)146);
            } else if (m_buf[0] == 'D') {
                // diagram
                m_curGame.addNag((short)201);
            }
            move = Move.NO_MOVE;
        } else if (m_lastTokenLength>=2 && (m_buf[0]=='+' || m_buf[0]=='-' || m_buf[0]=='=')) {
            // ignore incorrect direct NAGs for now
            move = Move.NO_MOVE;
        } else {
            char ch = m_buf[0];
            if (ch >= 'a' && ch <= 'h') {
                /*---------- pawn move ----------*/
                int col = Chess.NO_COL;
                if (1 > last)
                    syntaxError("Illegal pawn move");
                if (last >= 3 && (m_buf[1] >= '1' && m_buf[1] <= '8') &&
                                 (m_buf[2] >= 'a' && m_buf[2] <= 'h')) {
                    // long algebraic pawn move like b2b4
                    next = 2;
                } else if (last >= 4 && (m_buf[2] == '-' || m_buf[2] == 'x') && m_buf[3] >= 'a' && m_buf[3] <= 'h') {
                    /* long algebraic pawn move like b2-b4 or e5xd4 */
                    if (m_buf[0] != m_buf[3]) {
                        // not the same column --> capture
                        col = Chess.charToCol(ch);
                    }
                    next = 3;
                } else {
                    if (m_buf[1] == 'x') {
                        col = Chess.charToCol(ch);
                        next = 2;
                    }
                }
                if (next + 1 > last)
                    syntaxError("Illegal pawn move, no destination square");
                int toSqi = Chess.strToSqi(m_buf[next], m_buf[next + 1]);
                next += 2;

                int promo = Chess.NO_PIECE;
                if (next <= last) {
                    if (m_buf[next] == '=' && next < last) {
                        if (next < last) {
                            promo = Chess.charToPiece(m_buf[next + 1]);
                        }
                    } else {
                        promo = Chess.charToPiece(m_buf[next]);
                    }
                }
                move = m_curGame.getPosition().getPawnMove(col, toSqi, promo);
            } else {
                /*---------- non-pawn move ----------*/
                int piece = Chess.charToPiece(ch);

                if (last < 2)
                    syntaxError("Wrong move, no destination square");
                int toSqi = Chess.strToSqi(m_buf[last - 1], m_buf[last]);
                last -= 2;

                if (m_buf[last] == 'x')
                    last--; // capturing

                int row = Chess.NO_ROW, col = Chess.NO_COL;
                while (last >= 1) {
                    char rowColChar = m_buf[last];
                    int r = Chess.charToRow(rowColChar);
                    if (r != Chess.NO_ROW) {
                        row = r;
                        last--;
                    } else {
                        int c = Chess.charToCol(rowColChar);
                        if (c != Chess.NO_COL) {
                            col = c;
                        } else {
                            warning("Unknown char '" + rowColChar
                                    + "', row / column expected");
                        }
                        last--;
                    }
                }
                move = m_curGame.getPosition().getPieceMove(piece, col, row,
                        toSqi);
            }
        }
        if (DEBUG)
            System.out.println("  -> " + Move.getString(move));
        return move;
    }

    private static boolean isNAGStart(int ch) {
        return ch == TOK_NAG_BEGIN || ch == '!' || ch == '?';
    }

    private void parseNAG() throws PGNSyntaxError, IOException {
        // pre: NAG begin is current token
        // post: current Token is next after NAG
        if (getLastToken() == TOK_NAG_BEGIN) {
            getNextToken();
            if (isLastTokenInt()) {
                short nag = (short) getLastTokenAsInt();
                if (nag > 0) {
                    m_curGame.addNag(nag);
                }
            } else {
                syntaxError("Illegal NAG: number expected");
            }
            getNextToken();
        } else if (getLastToken() == '!' || getLastToken() == '?') {
            StringBuffer nagSB = new StringBuffer();
            do {
                nagSB.append((char) getLastToken());
                getNextToken();
            } while (getLastToken() == '!' || getLastToken() == '?');
            try {
                short nag = NAG.ofString(nagSB.toString());
                warning("Direct NAG used " + nagSB.toString() + " -> $" + nag);
                m_curGame.addNag(nag);
            } catch (IllegalArgumentException ex) {
                syntaxError("Illegal direct NAG " + nagSB.toString());
            }
        } else {
            syntaxError("NAG begin expected");
        }
    }

    private short parseHalfMove() throws PGNSyntaxError,
            IOException {
        short move = 1;
        if (isLastTokenMoveNumber()) {
            while (getNextToken() == TOK_PERIOD);
        }
        try {
            move = getLastTokenAsMove();
            if (move!=Move.NO_MOVE) {
                m_curGame.getPosition().doMove(move);
            }
        } catch (IllegalMoveException ex) {
            syntaxError(ex.getMessage());
        }

        return move;
    }

    private void parseMovetextSection() throws PGNSyntaxError, IOException {
        String comment = "";
        int level = 0;
        boolean goneBack = false;
        boolean newLine = false;
        Map<Integer, Integer> lines = new HashMap<Integer, Integer>();
        while (!isLastTokenResult()) {
            if (getLastToken() == TOK_LINE_BEGIN) {
            	newLine = true;
                addPostMoveComment(comment);
                comment = "";
                // remember current node to go back to later
                lines.put(level, m_curGame.getCurNode());
                m_curGame.getPosition().undoMove();
                level++;
                getNextToken();
            } else if (getLastToken() == TOK_LINE_END) {
            	newLine = false;
                addPostMoveComment(comment);
                comment = "";
                level--;
                if (level >= 0) {
                    // go back to last node before starting the variation
                    m_curGame.goBackToMainLine();
                    if (m_curGame.getCurNode() != lines.get(level)) {
                        m_curGame.gotoNode(lines.get(level));
                    }
                	goneBack = true;
                } else {
                    syntaxError("Unexpected variation end");
                }
                getNextToken();
            } else if (getLastToken() == TOK_COMMENT_BEGIN) {
            	if (comment.length() > 0) {
            		comment = comment + " " + getLastTokenAsString();
            	} else {
            		comment = getLastTokenAsString();
            	}
            	if (!newLine && !goneBack) {
	            	addPostMoveComment(comment);
	            	comment = "";
            	}
            	goneBack = false;
                getNextToken();
            } else if (isNAGStart(getLastToken())) {
                parseNAG();
            } else {
            	newLine = false;
                parseHalfMove();
                addPreMoveComment(comment);
                comment = "";
                getNextToken();
            }
        }
        addPostMoveComment(comment);
        getLastTokenAsResult(); // check
        if (level != 0)
            syntaxError("Unfinished variations in game: " + level);
    }

    private void addPreMoveComment(String comment) {
        if (comment.length() > 0) {
    		m_curGame.addPreMoveComment(comment.trim().replaceAll(" +", " "));
        }
    }

    private void addPostMoveComment(String comment) {
        if (comment.length() > 0) {
            m_curGame.addPostMoveComment(comment.trim().replaceAll(" +", " "));
        }
    }

    // ======================================================================

    /**
     * Returns the next game in the current pgn file.
     *
     * @return the next game
     */
    public Game parseGame() throws PGNSyntaxError, IOException {
        if (DEBUG)
            System.out.println("===> new game");
        if (m_in == null)
            return null;
        try {
            m_curGame = null;
            if (!findNextGameStart()) {
                return null;
            }
            m_curGame = new Game();
            m_curGame.setAlwaysAddLine(true);
            initForHeader();
            parseTagPairSection();
            initForMovetext();
            parseMovetextSection();
            m_curGame.pack();
        } catch (PGNSyntaxError ex) {
            System.out.println(ex); // sent to a listener in syntaxError
            // TODO: do something with this error
            if (ex.getSeverity()==PGNSyntaxError.ERROR && !ex.getLastToken().equals("EOF")) {
            	m_curGame.setError(true);
                m_curGame.addPostMoveComment("-error");
                m_curGame.pack();
                return m_curGame;
            }
        }
        return m_curGame;
    }

    // ======================================================================

    private static void usage() {
        System.out.println("PGNReader [-chars -tokens | -direct] {filename}");
        System.exit(0);
    }

    public static void main(String[] args) {
        int SHOW_CHARS = 0;
        int SHOW_TOKENS = 1;
        int PARSE_DIRECTLY = 2;
        int mode = PARSE_DIRECTLY;
        boolean verbose = false;

        int index = 0;
        while (index < args.length && args[index].startsWith("-")) {
            if (args[index].equals("-chars")) {
                mode = SHOW_CHARS;
                index++;
            } else if (args[index].equals("-tokens")) {
                mode = SHOW_TOKENS;
                index++;
            } else if (args[index].equals("-direct")) {
                mode = PARSE_DIRECTLY;
                index++;
            } else if (args[index].equals("-verbose")) {
                verbose = true;
                index++;
            } else {
                usage();
            }
        }

        try {
            if (index >= args.length) {
                usage();
            }

            for (; index < args.length; index++) {
                int numOfGames = 0;
                int numOfGamesWithResult = 0;
                PGNReader reader = new PGNReader(args[index]);
                reader.setErrorHandler(new PGNSimpleErrorHandler(System.out));
                if (mode == SHOW_CHARS) {
                    do {
                        System.out.println((char) reader.getChar());
                    } while (reader.m_lastChar != TOK_EOF);
                } else if (mode == SHOW_TOKENS) {
                    do {
                        reader.getNextToken();
                        System.out.println(reader.getLastTokenAsDebugString());
                    } while (reader.getLastToken() != TOK_EOF);
                } else if (mode == PARSE_DIRECTLY) {
                    long time = System.currentTimeMillis();
                    for (;;) {
                        Game game = reader.parseGame();
                        if (game == null)
                            break;
                        if (verbose) {
                            System.out.println(game);
                        } else {
                            System.out.print(".");
                        }
                        numOfGames++;
                        int res = game.getResult();
                        if (res == Chess.RES_WHITE_WINS
                                || res == Chess.RES_DRAW
                                || res == Chess.RES_BLACK_WINS) {
                            numOfGamesWithResult++;
                        }
                    }
                    System.out.println(numOfGames + " games found, "
                            + numOfGamesWithResult + " with result");
                    time = System.currentTimeMillis() - time;
                    System.out.println(time + "ms  "
                            + (1000 * numOfGames / time) + " games / s ");
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

}

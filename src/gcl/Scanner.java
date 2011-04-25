package gcl;

import java.io.FileWriter;
import java.io.Writer;
import java.io.PrintWriter;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;
//GCL Version 2011

class Token {
	int kind;    // token kind
	int pos;     // token position in the source text (starting at 0)
	int col;     // token column (starting at 1)
	int line;    // token line (starting at 1)
	String val;  // token value
	Token next;  // ML 2005-03-11 Peek tokens are kept in linked list
	public String spelling(){ return val;}
	public int line() { return line;}
	public int column() { return col;}
}

//------------------------------------------------------------------------------
// Buffer
//------------------------------------------------------------------------------
class Buffer {
	// This Buffer supports the following cases:
	// 1) seekable stream (file)
	//    a) whole stream in buffer
	//    b) part of stream in buffer
	// 2) non seekable stream (network, console)

	public static final int EOF = Character.MAX_VALUE + 1;
	private static final int MIN_BUFFER_LENGTH = 1024; // 1KB
	private static final int MAX_BUFFER_LENGTH = MIN_BUFFER_LENGTH * 64; // 64KB
	private byte[] buf;   // input buffer
	private int bufStart; // position of first byte in buffer relative to input stream
	private int bufLen;   // length of buffer
	private int fileLen;  // length of input stream (may change if stream is no file)
	private int bufPos;      // current position in buffer
	private RandomAccessFile file; // input stream (seekable)
	private InputStream stream; // growing input stream (e.g.: console, network)
	Scanner scanner;

	public Buffer(InputStream s, Scanner scanner) {
		this.scanner = scanner;
		stream = s;
		fileLen = bufLen = bufStart = bufPos = 0;
		buf = new byte[MIN_BUFFER_LENGTH];
	}

	public Buffer(String fileName, Scanner scanner) {
		this.scanner = scanner;
		try {
			file = new RandomAccessFile(fileName, "r");
			fileLen = (int) file.length();
			bufLen = Math.min(fileLen, MAX_BUFFER_LENGTH);
			buf = new byte[bufLen];
			bufStart = Integer.MAX_VALUE; // nothing in buffer so far
			if (fileLen > 0) setPos(0); // setup buffer to position 0 (start)
			else bufPos = 0; // index 0 is already after the file, thus setPos(0) is invalid
			if (bufLen == fileLen) Close();
		} catch (IOException e) {
			throw new FatalError("Could not open file " + fileName);
		}
	}

	// don't use b after this call anymore
	// called in UTF8Buffer constructor
	protected Buffer(Buffer b, Scanner scanner) {
		this.scanner = scanner;
		buf = b.buf;
		bufStart = b.bufStart;
		bufLen = b.bufLen;
		fileLen = b.fileLen;
		bufPos = b.bufPos;
		file = b.file;
		stream = b.stream;
		// keep finalize from closing the file
		b.file = null;
	}

	protected void finalize() throws Throwable {
		super.finalize();
		Close();
	}

	protected void Close() {
		if (file != null) {
			try {
				file.close();
				file = null;
			} catch (IOException e) {
				throw new FatalError(e.getMessage());
			}
		}
	}

	static boolean showLine = false;

	public int Read() {
		if (bufPos < bufLen) {
			final char eol = '\n'; // jbergin 10/05
			if (showLine) // Assumes dos end lines. 
			{	int i = bufPos + 1, j;
				int size = 0;
				for(;i < bufLen && buf[i] != eol; ++i) size++;
				char s [] = new char[size + 1];
				for(i = bufPos,j = 0; i < bufLen && buf[i] != eol; i++, j++) 	
					s[j] = (char)buf[i];
				scanner.outFile().println(s);
				showLine = false;
			}
			if(buf[bufPos] == eol) showLine = true;
			return buf[bufPos++] & 0xff;  // mask out sign bits
		} else if (getPos() < fileLen) {
			setPos(getPos());         // shift buffer start to pos
			return buf[bufPos++] & 0xff; // mask out sign bits
		} else if (stream != null && ReadNextStreamChunk() > 0) {
			return buf[bufPos++] & 0xff;  // mask out sign bits
		} else {
			return EOF;
		}
	}

	public int Peek() {
		int curPos = getPos();
		int ch = Read();
		setPos(curPos);
		return ch;
	}

	public String GetString(int beg, int end) {
	    int len = end - beg;
	    char[] buf = new char[len];
	    int oldPos = getPos();
	    setPos(beg);
	    for (int i = 0; i < len; ++i) buf[i] = (char) Read();
	    setPos(oldPos);
	    return new String(buf);
	}

	public int getPos() {
		return bufPos + bufStart;
	}

	public void setPos(int value) {
		if (value >= fileLen && stream != null) {
			// Wanted position is after buffer and the stream
			// is not seek-able e.g. network or console,
			// thus we have to read the stream manually till
			// the wanted position is in sight.
			while (value >= fileLen && ReadNextStreamChunk() > 0);
		}

		if (value < 0 || value > fileLen) {
			throw new FatalError("buffer out of bounds access, position: " + value);
		}

		if (value >= bufStart && value < bufStart + bufLen) { // already in buffer
			bufPos = value - bufStart;
		} else if (file != null) { // must be swapped in
			try {
				file.seek(value);
				bufLen = file.read(buf);
				bufStart = value; bufPos = 0;
			} catch(IOException e) {
				throw new FatalError(e.getMessage());
			}
		} else {
			// set the position to the end of the file, Pos will return fileLen.
			bufPos = fileLen - bufStart;
		}
	}
	
	// Read the next chunk of bytes from the stream, increases the buffer
	// if needed and updates the fields fileLen and bufLen.
	// Returns the number of bytes read.
	private int ReadNextStreamChunk() {
		int free = buf.length - bufLen;
		if (free == 0) {
			// in the case of a growing input stream
			// we can neither seek in the stream, nor can we
			// foresee the maximum length, thus we must adapt
			// the buffer size on demand.
			byte[] newBuf = new byte[bufLen * 2];
			System.arraycopy(buf, 0, newBuf, 0, bufLen);
			buf = newBuf;
			free = bufLen;
		}
		
		int read;
		try { read = stream.read(buf, bufLen, free); }
		catch (IOException ioex) { throw new FatalError(ioex.getMessage()); }
		
		if (read > 0) {
			fileLen = bufLen = (bufLen + read);
			return read;
		}
		// end of stream reached
		return 0;
	}
}

//------------------------------------------------------------------------------
// UTF8Buffer
//------------------------------------------------------------------------------
class UTF8Buffer extends Buffer {
	UTF8Buffer(Buffer b, Scanner scanner) { super(b, scanner); }

	public int Read() {
		int ch;
		do {
			ch = super.Read();
			// until we find a utf8 start (0xxxxxxx or 11xxxxxx)
		} while ((ch >= 128) && ((ch & 0xC0) != 0xC0) && (ch != EOF));
		if (ch < 128 || ch == EOF) {
			// nothing to do, first 127 chars are the same in ascii and utf8
			// 0xxxxxxx or end of file character
		} else if ((ch & 0xF0) == 0xF0) {
			// 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
			int c1 = ch & 0x07; ch = super.Read();
			int c2 = ch & 0x3F; ch = super.Read();
			int c3 = ch & 0x3F; ch = super.Read();
			int c4 = ch & 0x3F;
			ch = (((((c1 << 6) | c2) << 6) | c3) << 6) | c4;
		} else if ((ch & 0xE0) == 0xE0) {
			// 1110xxxx 10xxxxxx 10xxxxxx
			int c1 = ch & 0x0F; ch = super.Read();
			int c2 = ch & 0x3F; ch = super.Read();
			int c3 = ch & 0x3F;
			ch = (((c1 << 6) | c2) << 6) | c3;
		} else if ((ch & 0xC0) == 0xC0) {
			// 110xxxxx 10xxxxxx
			int c1 = ch & 0x1F; ch = super.Read();
			int c2 = ch & 0x3F;
			ch = (c1 << 6) | c2;
		}
		return ch;
	}
}

//------------------------------------------------------------------------------
// StartStates  -- maps characters to start states of tokens
//------------------------------------------------------------------------------
class StartStates {
	private static class Elem {
		public int key, val;
		public Elem next;
		public Elem(int key, int val) { this.key = key; this.val = val; }
	}

	private Elem[] tab = new Elem[128];

	public void set(int key, int val) {
		Elem e = new Elem(key, val);
		int k = key % 128;
		e.next = tab[k]; tab[k] = e;
	}

	public int state(int key) {
		Elem e = tab[key % 128];
		while (e != null && e.key != key) e = e.next;
		return e == null ? 0: e.val;
	}
}

//------------------------------------------------------------------------------
// Scanner
//------------------------------------------------------------------------------
public class Scanner {
	static final char EOL = '\n';
	static final int  eofSym = 0;
	static final int maxT = 48;
	static final int noSym = 48;


	private PrintWriter out;
	public Buffer buffer; // scanner buffer

	Token t;           // current token
	int ch;            // current input character
	int pos;           // byte position of current character
	int col;           // column number of current character
	int line;          // line number of current character
	int oldEols;       // EOLs that appeared in a comment;
	static final StartStates start; // maps initial token character to start state
	static final Map<String, Integer>  literals; // maps literal strings to literal kinds

	Token tokens;      // list of tokens already peeked (first token is a dummy)
	Token pt;          // current peek token
	
	char[] tval = new char[16]; // token text used in NextToken(), dynamically enlarged
	int tlen;          // length of current token
	
	static {
		start = new StartStates();
		literals = new HashMap<String, Integer> ();
		for (int i = 65; i <= 90; ++i) start.set(i, 1);
		for (int i = 97; i <= 122; ++i) start.set(i, 1);
		for (int i = 48; i <= 57; ++i) start.set(i, 2);
		start.set(39, 3); 
		start.set(34, 4); 
		start.set(36, 16); 
		start.set(46, 40); 
		start.set(59, 19); 
		start.set(61, 20); 
		start.set(44, 21); 
		start.set(91, 41); 
		start.set(93, 23); 
		start.set(58, 24); 
		start.set(45, 42); 
		start.set(124, 28); 
		start.set(38, 29); 
		start.set(43, 30); 
		start.set(126, 31); 
		start.set(40, 32); 
		start.set(41, 33); 
		start.set(35, 34); 
		start.set(62, 43); 
		start.set(60, 44); 
		start.set(42, 37); 
		start.set(47, 38); 
		start.set(92, 39); 
		start.set(Buffer.EOF, -1);
		literals.put("module", new Integer(4));
		literals.put("private", new Integer(5));
		literals.put("begin", new Integer(7));
		literals.put("end", new Integer(8));
		literals.put("constant", new Integer(10));
		literals.put("range", new Integer(13));
		literals.put("typedefinition", new Integer(17));
		literals.put("integer", new Integer(18));
		literals.put("Boolean", new Integer(19));
		literals.put("tuple", new Integer(20));
		literals.put("skip", new Integer(21));
		literals.put("read", new Integer(22));
		literals.put("write", new Integer(23));
		literals.put("do", new Integer(25));
		literals.put("od", new Integer(26));
		literals.put("if", new Integer(27));
		literals.put("fi", new Integer(28));
		literals.put("true", new Integer(46));
		literals.put("false", new Integer(47));

	}
	
	public Scanner (String fileName, String outFile) {
		buffer = new Buffer(fileName, this);
		try{out = new PrintWriter(new FileWriter(outFile));}catch(Exception xx){System.exit(0);}
		Init();
	}
	
	public Scanner(InputStream s, Writer outFile) {
		buffer = new Buffer(s, this);
		try{out = new PrintWriter(outFile);}catch(Exception xx){System.exit(0);}
		Init();
	}
	
	public PrintWriter outFile(){
		return out;
	}
	
	public Token currentToken(){
		return t;
	}

	void Init () {
		out.println("Compiled on: " + new Date());
		pos = -1; line = 1; col = 0;
		oldEols = 0;
		NextCh();
		if (ch == 0xEF) { // check optional byte order mark for UTF-8
			NextCh(); int ch1 = ch;
			NextCh(); int ch2 = ch;
			if (ch1 != 0xBB || ch2 != 0xBF) {
				throw new FatalError("Illegal byte order mark at start of file");
			}
			buffer = new UTF8Buffer(buffer, this); col = 0;
			NextCh();
		}
		pt = tokens = new Token();  // first token is a dummy
	}
	
	void NextCh() {
		if (oldEols > 0) { ch = EOL; oldEols--; }
		else {
			pos = buffer.getPos();
			ch = buffer.Read(); col++;
			// replace isolated '\r' by '\n' in order to make
			// eol handling uniform across Windows, Unix and Mac
			if (ch == '\r' && buffer.Peek() != '\n') ch = EOL;
			if (ch == EOL) { line++; col = 0; }
		}

	}
	
	void AddCh() {
		if (tlen >= tval.length) {
			char[] newBuf = new char[2 * tval.length];
			System.arraycopy(tval, 0, newBuf, 0, tval.length);
			tval = newBuf;
		}
		if (ch != Buffer.EOF) {
			tval[tlen++] = (char)ch; 

			NextCh();
		}

	}
	

	boolean Comment0() {
		int level = 1, pos0 = pos, line0 = line, col0 = col;
		NextCh();
		if (ch == '-') {
			NextCh();
			for(;;) {
				if (ch == 10) {
					level--;
					if (level == 0) { oldEols = line - line0; NextCh(); return true; }
					NextCh();
				} else if (ch == Buffer.EOF) return false;
				else NextCh();
			}
		} else {
			buffer.setPos(pos0); NextCh(); line = line0; col = col0;
		}
		return false;
	}

	boolean Comment1() {
		int level = 1, pos0 = pos, line0 = line, col0 = col;
		NextCh();
		if (ch == '-') {
			NextCh();
			for(;;) {
				if (ch == 13) {
					level--;
					if (level == 0) { oldEols = line - line0; NextCh(); return true; }
					NextCh();
				} else if (ch == Buffer.EOF) return false;
				else NextCh();
			}
		} else {
			buffer.setPos(pos0); NextCh(); line = line0; col = col0;
		}
		return false;
	}

	boolean Comment2() {
		int level = 1, pos0 = pos, line0 = line, col0 = col;
		NextCh();
			for(;;) {
				if (ch == '}') {
					level--;
					if (level == 0) { oldEols = line - line0; NextCh(); return true; }
					NextCh();
				} else if (ch == '{') {
					level++; NextCh();
				} else if (ch == Buffer.EOF) return false;
				else NextCh();
			}
	}


	void CheckLiteral() {
		String val = t.val;

		Object kind = literals.get(val);
		if (kind != null) {
			t.kind = ((Integer) kind).intValue();
		}
	}

	Token NextToken() {
		while (ch == ' ' ||
			ch >= 9 && ch <= 10 || ch == 13
		) NextCh();
		if (ch == '-' && Comment0() ||ch == '-' && Comment1() ||ch == '{' && Comment2()) return NextToken();
		t = new Token();
		t.pos = pos; t.col = col; t.line = line; 
		int state = start.state(ch);
		tlen = 0; AddCh();

		loop: for (;;) {
			switch (state) {
				case -1: { t.kind = eofSym; break loop; } // NextCh already done 
				case 0: { t.kind = noSym; break loop; }   // NextCh already done
				case 1:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'Z' || ch == '_' || ch >= 'a' && ch <= 'z') {AddCh(); state = 1; break;}
					else {t.kind = 1; t.val = new String(tval, 0, tlen); CheckLiteral(); return t;}
				case 2:
					if (ch >= '0' && ch <= '9') {AddCh(); state = 2; break;}
					else {t.kind = 2; break loop;}
				case 3:
					if (ch >= 1 && ch <= 9 || ch >= 11 && ch <= 12 || ch >= 14 && ch <= '&' || ch >= '(' && ch <= 65535) {AddCh(); state = 3; break;}
					else if (ch == 39) {AddCh(); state = 5; break;}
					else {t.kind = noSym; break loop;}
				case 4:
					if (ch >= 1 && ch <= 9 || ch >= 11 && ch <= 12 || ch >= 14 && ch <= '!' || ch >= '#' && ch <= 65535) {AddCh(); state = 4; break;}
					else if (ch == '"') {AddCh(); state = 5; break;}
					else {t.kind = noSym; break loop;}
				case 5:
					{t.kind = 3; break loop;}
				case 6:
					if (ch == '+' || ch == '-') {AddCh(); state = 7; break;}
					else {t.kind = noSym; break loop;}
				case 7:
					{t.kind = 49; break loop;}
				case 8:
					if (ch == '+' || ch == '-') {AddCh(); state = 9; break;}
					else {t.kind = noSym; break loop;}
				case 9:
					{t.kind = 50; break loop;}
				case 10:
					{t.kind = 51; break loop;}
				case 11:
					{t.kind = 52; break loop;}
				case 12:
					if (ch == '+' || ch == '-') {AddCh(); state = 13; break;}
					else {t.kind = noSym; break loop;}
				case 13:
					{t.kind = 53; break loop;}
				case 14:
					{t.kind = 54; break loop;}
				case 15:
					{t.kind = 55; break loop;}
				case 16:
					if (ch == 'C' || ch == 'c') {AddCh(); state = 6; break;}
					else if (ch == 'O' || ch == 'o') {AddCh(); state = 8; break;}
					else if (ch == 'S' || ch == 's') {AddCh(); state = 17; break;}
					else if (ch == 'M' || ch == 'm') {AddCh(); state = 12; break;}
					else if (ch == 'R' || ch == 'r') {AddCh(); state = 18; break;}
					else {t.kind = noSym; break loop;}
				case 17:
					if (ch == '+') {AddCh(); state = 10; break;}
					else if (ch == '-') {AddCh(); state = 11; break;}
					else {t.kind = noSym; break loop;}
				case 18:
					if (ch == '+') {AddCh(); state = 14; break;}
					else if (ch == '-') {AddCh(); state = 15; break;}
					else {t.kind = noSym; break loop;}
				case 19:
					{t.kind = 9; break loop;}
				case 20:
					{t.kind = 11; break loop;}
				case 21:
					{t.kind = 12; break loop;}
				case 22:
					{t.kind = 15; break loop;}
				case 23:
					{t.kind = 16; break loop;}
				case 24:
					if (ch == '=') {AddCh(); state = 25; break;}
					else {t.kind = noSym; break loop;}
				case 25:
					{t.kind = 24; break loop;}
				case 26:
					{t.kind = 29; break loop;}
				case 27:
					{t.kind = 30; break loop;}
				case 28:
					{t.kind = 31; break loop;}
				case 29:
					{t.kind = 32; break loop;}
				case 30:
					{t.kind = 33; break loop;}
				case 31:
					{t.kind = 35; break loop;}
				case 32:
					{t.kind = 36; break loop;}
				case 33:
					{t.kind = 37; break loop;}
				case 34:
					{t.kind = 38; break loop;}
				case 35:
					{t.kind = 40; break loop;}
				case 36:
					{t.kind = 42; break loop;}
				case 37:
					{t.kind = 43; break loop;}
				case 38:
					{t.kind = 44; break loop;}
				case 39:
					{t.kind = 45; break loop;}
				case 40:
					if (ch == '.') {AddCh(); state = 22; break;}
					else {t.kind = 6; break loop;}
				case 41:
					if (ch == ']') {AddCh(); state = 26; break;}
					else {t.kind = 14; break loop;}
				case 42:
					if (ch == '>') {AddCh(); state = 27; break;}
					else {t.kind = 34; break loop;}
				case 43:
					if (ch == '=') {AddCh(); state = 35; break;}
					else {t.kind = 39; break loop;}
				case 44:
					if (ch == '=') {AddCh(); state = 36; break;}
					else {t.kind = 41; break loop;}

			}
		}
		t.val = new String(tval, 0, tlen);
		return t;
	}
	
	// get the next token (possibly a token already seen during peeking)
	public Token Scan () {
		if (tokens.next == null) {
			return NextToken();
		} else {
			pt = tokens = tokens.next;
			return tokens;
		}
	}

	// get the next token, ignore pragmas
	public Token Peek () {
		do {
			if (pt.next == null) {
				pt.next = NextToken();
			}
			pt = pt.next;
		} while (pt.kind > maxT); // skip pragmas

		return pt;
	}

	// make sure that peeking starts at current scan position
	public void ResetPeek () { pt = tokens; }

} // end Scanner


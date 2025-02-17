package com.adobedam.core.dms.filters;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Response Wrapper to capture and modify JSON output.
 */
public class ResponseWrapper extends HttpServletResponseWrapper {

    private final CharArrayWriter charArrayWriter = new CharArrayWriter();
    private final PrintWriter writer = new PrintWriter(charArrayWriter);

    public ResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public PrintWriter getWriter() {
        return writer;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // Not implemented
            }

            @Override
            public void write(int b) throws IOException {
                charArrayWriter.write(b);
            }
        };
    }

    @Override
    public void flushBuffer() throws IOException {
        writer.flush();
    }

    @Override
    public String toString() {
        return charArrayWriter.toString();
    }
}
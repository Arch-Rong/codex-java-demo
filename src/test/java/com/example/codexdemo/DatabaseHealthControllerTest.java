package com.example.codexdemo;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseHealthControllerTest {
    @Test
    void successfulCheckClosesConnection() throws SQLException {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        var response = new DatabaseHealthController(source).health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("ok", response.getBody().status());
        verify(connection).close();
    }

    @Test
    void unavailableDatabaseReturns503WithoutLeakingDriverError() throws SQLException {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("internal connection details"));

        var response = new DatabaseHealthController(source).health();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("数据库暂不可用", response.getBody().message());
    }
}

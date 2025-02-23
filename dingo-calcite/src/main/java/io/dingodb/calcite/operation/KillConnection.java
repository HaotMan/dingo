/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.calcite.operation;

import lombok.Getter;

import java.sql.Connection;
import java.sql.SQLException;

public class KillConnection implements DdlOperation{

    Connection connection;

    private Integer threadId;

    public KillConnection(Integer threadId) {
        this.threadId = threadId;
    }

    public void initConnection(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void execute() {
        try {
            if (connection == null) {
                return;
            }
            connection.setClientInfo("@connection_kill", String.valueOf(threadId));
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public String getThreadId() {
        return threadId.toString();
    }

    public String getMysqlThreadId() {
        return "mysql:" + threadId.toString();
    }
}

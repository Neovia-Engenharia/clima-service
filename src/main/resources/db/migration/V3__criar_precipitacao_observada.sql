CREATE TABLE clima.hora_observada (
    id                           BIGINT         IDENTITY(1,1) NOT NULL,
    region_id                    BIGINT         NOT NULL,
    data_hora                    DATETIME2      NOT NULL,
    sem_dado                     BIT            NOT NULL,
    station_id                   BIGINT         NULL,
    station_name                 NVARCHAR(200)  NULL,
    station_code                 NVARCHAR(100)  NULL,
    precipitation_sum            DECIMAL(12,4)  NULL,
    precipitation_max            DECIMAL(12,4)  NULL,
    precipitation_max_datetime   DATETIME2      NULL,
    precipitation_first          DECIMAL(12,4)  NULL,
    precipitation_first_datetime DATETIME2      NULL,
    is_interpolated              BIT            NULL,
    obtido_em                    DATETIME2      NOT NULL,
    CONSTRAINT pk_hora_observada PRIMARY KEY (id),
    CONSTRAINT fk_hora_observada_regiao FOREIGN KEY (region_id) REFERENCES clima.regiao (region_id),
    CONSTRAINT uq_hora_observada UNIQUE (region_id, data_hora)
);

CREATE TABLE clima.tentativa_observado (
    region_id           BIGINT     NOT NULL,
    data_hora           DATETIME2  NOT NULL,
    ultima_tentativa_em DATETIME2  NOT NULL,
    CONSTRAINT pk_tentativa_observado PRIMARY KEY (region_id, data_hora),
    CONSTRAINT fk_tentativa_observado_regiao FOREIGN KEY (region_id) REFERENCES clima.regiao (region_id)
);

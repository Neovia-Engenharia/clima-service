CREATE TABLE clima.rodada_previsao (
    id            BIGINT         IDENTITY(1,1) NOT NULL,
    region_id     BIGINT         NOT NULL,
    run_datetime  DATETIME2      NOT NULL,
    obtido_em     DATETIME2      NOT NULL,
    CONSTRAINT pk_rodada_previsao PRIMARY KEY (id),
    CONSTRAINT fk_rodada_previsao_regiao FOREIGN KEY (region_id) REFERENCES clima.regiao (region_id),
    CONSTRAINT uq_rodada_previsao_regiao_run UNIQUE (region_id, run_datetime)
);

CREATE TABLE clima.ponto_previsao (
    id            BIGINT         IDENTITY(1,1) NOT NULL,
    rodada_id     BIGINT         NOT NULL,
    data_hora     DATETIME2      NOT NULL,
    run_datetime  DATETIME2      NOT NULL,
    variaveis     NVARCHAR(MAX)  NOT NULL,
    CONSTRAINT pk_ponto_previsao PRIMARY KEY (id),
    CONSTRAINT fk_ponto_previsao_rodada FOREIGN KEY (rodada_id) REFERENCES clima.rodada_previsao (id),
    CONSTRAINT uq_ponto_previsao_rodada_data UNIQUE (rodada_id, data_hora)
);

-- ultima_tentativa_em fica NULL até a primeira tentativa: a linha é criada antes, para poder ser travada
CREATE TABLE clima.tentativa_previsao (
    region_id           BIGINT     NOT NULL,
    ultima_tentativa_em DATETIME2  NULL,
    ultima_falhou       BIT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_tentativa_previsao PRIMARY KEY (region_id),
    CONSTRAINT fk_tentativa_previsao_regiao FOREIGN KEY (region_id) REFERENCES clima.regiao (region_id)
);

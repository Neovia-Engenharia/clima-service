CREATE TABLE clima.regiao (
    region_id     BIGINT         NOT NULL,
    nome          NVARCHAR(200)  NOT NULL,
    alias         NVARCHAR(100)  NOT NULL,
    inativa       BIT            NOT NULL,
    estado_id     BIGINT         NOT NULL,
    removida      BIT            NOT NULL DEFAULT 0,
    atualizado_em DATETIME2      NOT NULL,
    CONSTRAINT pk_regiao PRIMARY KEY (region_id)
);

CREATE TABLE clima.catalogo_regioes_busca (
    id            BIGINT         IDENTITY(1,1) NOT NULL,
    obtido_em     DATETIME2      NOT NULL,
    total_regioes INT            NOT NULL,
    CONSTRAINT pk_catalogo_regioes_busca PRIMARY KEY (id)
);

CREATE TABLE clima.catalogo_regioes_controle (
    id              INT          NOT NULL,
    ultima_falha_em DATETIME2    NULL,
    CONSTRAINT pk_catalogo_regioes_controle PRIMARY KEY (id)
);

INSERT INTO clima.catalogo_regioes_controle (id, ultima_falha_em) VALUES (1, NULL);

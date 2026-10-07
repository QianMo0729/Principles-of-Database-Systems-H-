# Principles of Database Systems (H): Projects

Course projects for Principles of Database Systems (H), Fall 2026. Each project lives in its own folder.

| Project | Topic | Links |
|---|---|---|
| [Project1](Project1/) | DBMS performance evaluation: file I/O, PostgreSQL and openGauss compared on speed, reliability and the smallest server each needs | [Introduction](#project-1-dbms-performance-evaluation) · [Report (in Chinese)](Project1/Report/report.pdf) |

## Project 1: DBMS Performance Evaluation

Folder: [`Project1/`](Project1/). Report: [`Report/report.pdf`](Project1/Report/report.pdf) (in Chinese). Results:
[`output/summary/RESULTS.md`](Project1/output/summary/RESULTS.md). Paths and commands in this section are relative to `Project1/`.

Experiments for two questions:

1. What does a DBMS offer that data operations in files do not?
2. PostgreSQL or openGauss: which is better, and by which standard?

The second question is answered by three standards: **performance**, **reliability**, and **cost**
(the smallest server each system needs). Results are in `output/summary/RESULTS.md` and
`output/summary/figures/`.

### Layout

```
Project1/
├── Codes/
│   ├── java/src/dbbench/     the measurement program (one code base for file, PostgreSQL, openGauss)
│   │   ├── file/             data operations directly on files
│   │   ├── db/               the same operations through JDBC, load generator, reliability tests
│   │   ├── data/             raw IMDb dumps -> cleaned files
│   │   ├── bench/, util/     shared workload definition, CSV output, statistics
│   ├── java/lib/             JDBC drivers (PostgreSQL 42.7.13, openGauss 6.0.6-og)
│   ├── config/
│   │   ├── experiment.env    images, data scales, repetitions, client counts
│   │   └── specs/*.env       one file per VM spec: CPU, memory, and the settings sized for it
│   ├── scripts/              one script per experiment (see below)
│   └── analysis/
│       ├── summarize.py      raw CSV -> summary tables (CSV + RESULTS.md)
│       ├── plot.py           summary -> figures (vector PDF + 300 dpi PNG)
│       ├── chartstyle.py     the shared look of the figures, and a check for overlapping text
│       └── fonts/            IBM Plex (SIL Open Font License)
├── Report/
│   ├── report.tex, report.pdf          the report (in Chinese), built with XeLaTeX
│   └── figures/                        figures without a title block (the caption carries it)
└── output/
    ├── vm-4c8g/              results are grouped by the VM spec they were measured on
    │   ├── raw/              one row per measured value
    │   └── logs/             console logs, query plans, settings, environment
    ├── vm-2c4g/  vm-2c2g/  vm-1c1g/  vm-1c512m/
    └── summary/              tables (CSV + RESULTS.md) and figures across all specs
```

The code is shared by all VM specs; what differs per spec is its file in `Codes/config/specs/`
and its folder in `output/`.

### Environment

| | |
|---|---|
| Host | Apple M5 Pro, 18 cores, 24 GB, macOS 27 |
| VM | Colima 0.10.3 (macOS Virtualization.Framework), Ubuntu 24.04, Linux 6.8, arm64 |
| PostgreSQL | 18.6, official image `postgres:18` |
| openGauss | 6.0.0, image `enmotech/opengauss:6.0.0` |
| Java | host JDK 27; inside the VM `eclipse-temurin:25-jdk`; compiled with `--release 21` |

Everything runs natively on arm64; nothing is emulated. The official `opengauss/opengauss`
7.0.0-RC3 arm64 image does not start (`libopenblas.so.0` is missing), so the 6.0.0 image is used.

A "VM spec" is the size of the whole Linux machine: the VM is restarted with exactly that many
CPUs and that much memory, so the kernel, Docker and the database all live inside the budget, as on
a rented server of that size. Specs: `1c512m`, `1c1g`, `2c2g`, `2c4g`, `4c8g`.

### Data

IMDb non-commercial datasets (`title.basics`, `name.basics`, `title.ratings`), downloaded by
`02_data.sh` into a Docker volume. They are real data with film titles and person names, which is
what the project hints ask for. The data is not in this repository: IMDb's licence allows personal
and non-commercial use but not redistribution.

| scale | titles | people | ratings | how |
|---|---|---|---|---|
| s | 127,865 | 156,787 | 17,065 | 1 % sample |
| m | 1,285,110 | 1,573,840 | 171,407 | 10 % sample |
| l | 12,842,753 | 15,712,491 | 1,718,051 | everything |

Smaller scales are samples of the real rows (a row is kept when a hash of its id falls below the
percentage), never copies or generated rows. The cleaned files use PostgreSQL's COPY text format,
so the Java file programs and both databases read the same bytes.

### Experiments

| script | question | where the client runs |
|---|---|---|
| `10_file_vs_db.sh` | Retrieval and bulk update: file versus DBMS, at three scales; crash and concurrency on files (about 25 min) | inside the VM, next to the data |
| `20_pg_vs_og_load.sh` | Throughput and latency with 1 to 64 concurrent clients (about 25 min) | host |
| `30_reliability.sh` | Lost updates, duplicate inserts, kill -9 under load, error messages, ten minutes of sustained load, on the 2 CPU / 2 GiB spec (about 35 min) | host |
| `40_spec_ladder.sh` | The smallest machine each system can run on, and what it carries there (about 30 min) | host |
| `45_small_spec_diagnosis.sh` | Why a server fails on the smallest machines; settings a new install picks (2 min) | host |
| `90_analyze.sh` | Tables and figures (1 min) | container |
| `95_report_figures.sh` | Figures for the report, into `Report/figures` (English labels, the set the report uses) and `Report/figures_zh` (Chinese labels, not kept here) (1 min) | container |

Retrieval covers a primary-key lookup, exact match, prefix match and substring match, each with a
common, a rare and an absent value. The bulk update is the one from the project description:
replace every `T` in a person's name with `Ttt`.

### Running

Requirements: macOS on Apple silicon, Homebrew, a JDK (21 or newer).

```sh
brew install colima docker
Codes/scripts/run_all.sh          # everything, about two hours
```

Or step by step: `01_build.sh`, `02_data.sh`, then any experiment script. A quick trial that
leaves the real results alone:

```sh
SCALES=s REPS=2 OUTPUT_DIR="$PWD/output/_trial" Codes/scripts/10_file_vs_db.sh
```

To remove everything afterwards: `colima delete` (VM, images and data), `brew uninstall colima docker`.

### Figures

| file | shows |
|---|---|
| `fig01_retrieval` | Four kinds of lookup on 12.8 million titles: file, PostgreSQL, openGauss, with and without an index |
| `fig02_retrieval_scaling` | How lookup time grows from 128 thousand to 12.8 million rows |
| `fig03_bulk_update` | Changing 1.3 million names versus changing 10 |
| `fig04_update_space` | Table growth after the update: heap storage versus openGauss Ustore |
| `fig05_concurrent_load` | Throughput and latency from 1 to 64 clients |
| `fig06_server_requirements` | Memory, throughput and latency on each machine size |
| `fig07_where_it_runs` | Which machine sizes each system starts on, by configuration |
| `fig08_sustained_load` | Ten minutes of load on the 2 CPU / 2 GiB machine |
| `fig09_lost_updates` | Concurrent increments: what is lost in a file and in a database |
| `fig10_crash` | What survives a kill -9, and how long recovery takes |

The report is built with `cd Report && latexmk -xelatex report.tex`. It uses fonts that ship with
macOS: Songti, Heiti and Kaiti for Chinese, Times New Roman, Arial and Courier New for Latin text.

A bar marked `//` is cut short: bars on its side of the break are drawn to their own scale, so the
short bars can be compared with each other. The true value is always written at the end of the bar.

### What keeps the comparisons fair

- **Same data, verified.** Every retrieval records the row count and an id checksum; every update
  records rows changed, row count, total name bytes and rows containing `Ttt`. `RESULTS.md`
  reports whether file, PostgreSQL and openGauss agree.
- **Same place.** For file versus DBMS the file program, the JDBC client and the database run in
  the same VM on the same disk. For PostgreSQL versus openGauss the same client code runs on the
  host, so it does not consume the resources of the machine under test.
- **Same semantics.** Both databases use UTF-8 with the `C` collation, so string comparison is byte
  comparison, as in Java. openGauss databases are created with `DBCOMPATIBILITY 'PG'`.
- **Two configurations.** `default` is each system as its image installs it on the 4 CPU / 8 GiB
  machine. `tuned` sets the same memory parameters on both, sized for the spec (`shared_buffers` =
  a quarter of the memory, and so on; see `Codes/config/specs/`). Durability settings (`fsync`,
  `synchronous_commit`, `full_page_writes`) are on in both and never changed. openGauss's
  installer chooses `shared_buffers` by machine size (1 GB on 8 GiB, 32 MB on 1 GiB), so in the
  spec ladder `default` means "installed on the big machine, then moved unchanged"; a separate
  test checks whether a new install starts on each machine.
- **Repetition.** Queries are warmed up, then repeated; tables show the median. The two systems
  take turns in the load test, and the host's load average is recorded with every run.
  `RESULTS.md` lists every measurement group whose spread exceeds 20 %.

### Limits of these results

- The CPU and the SSD of this laptop are much faster than a typical rented server's. Relative
  results (who needs less, who is faster on the same machine) carry over; absolute throughput does not.
- Inside a VM on macOS, `fsync` reaches the host's page cache but is not guaranteed to reach the
  physical disk. The crash tests therefore cover a killed process, not a power failure.
- Reliability tests show how each system behaves in these scenarios. They are not a proof that one
  product is more reliable in general.
- One version of each system, one machine, one data set.

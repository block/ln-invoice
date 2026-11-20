Change Log
==========

Version 0.0.7 *(2026-11-19)*
----------------------------

* Rejects malformed invoices with missing or duplicate required fields, invalid descriptions, invalid signatures, or malformed tagged fields.
* Returns eager parsing failures through `Either` and preserves explicit payee keys with the required signature checks.
* Parses invoice amounts exactly and rejects overflow or amounts below millisatoshi precision.
* Rejects Bech32 and Bech32m inputs with surrounding junk or mixed-case suffixes.
* Enforces public API checks during builds and CI.
* Refreshes dependencies and documentation tooling while retaining Java 11 targets.

Version 0.0.6 *(2026-05-26)*
----------------------------

* Supports regtest payment requests <Lauren Shareshian>
* Fixes broken Maven Central badge in README <Jem Mawson>

Version 0.0.5 *(2026-03-14)*
----------------------------

* Reject invoices with unknown required feature bits <Ryan Loomba>

Version 0.0.4 *(2026-02-10)*
----------------------------

* Parses routing hints from payment requests <Hugo Muller-Downing>

Version 0.0.3 *(2026-01-14)*
----------------------------

* Supports signet <Oliver Hardt>

Version 0.0.2 *(2025-08-04)*
----------------------------

* Fixes parsing of uneven-byte length invoices. <Hugo Muller-Downing>

Version 0.0.1 *(2023-07-25)*
----------------------------

* Initial public release from internal snapshot. Parse Bech32 data & lightning invoices. <Jem Mawson> 

package com.ledgr.app.domain.model

/** docs/05 `account.type`. */
enum class AccountType { SAVINGS, CURRENT, CREDIT_CARD, WALLET }

/** docs/05 `category.kind`. */
enum class CategoryKind { EXPENSE, INCOME, TRANSFER }

/** docs/05 `own_identity.kind`: own VPAs and own name spellings, used for own-transfer detection. */
enum class OwnIdentityKind { UPI_ID, NAME_VARIANT }

/** docs/05 `counterparty.type`. */
enum class CounterpartyType { MERCHANT, PERSON, OWN_ACCOUNT, BANK, UNKNOWN }

/** docs/05 `counterparty_alias.created_by`. */
enum class AliasSource { USER, RULE }

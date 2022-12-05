# payment-hub-ee
Payment Hub Enterprise Edition middleware for integration to real-time payment systems. 

# Zeebe worker api mapping

BPMN|Worker|Route|Endpoint|HTTP METHOD
--|--|--|--|--
party-registration-DFSPID.bpmn	|party-registration-oracle	|direct:get-dfsp-from-oracle	|/oracle/participants/{PARTY_ID_TYPE}/${PARTY_ID}?host={oracle-host}	|GET
party-registration-DFSPID.bpmn	|party-registration-oracle	|direct:remove-party-identifier-from-dfsp-in-oracle	|/oracle/participants/{PARTY_ID_TYPE}/${PARTY_ID}?host={oracle-host}	|DELETE
party-registration-DFSPID.bpmn	|party-registration-oracle	|direct:add-party-identifier-to-dfsp-in-oracle	|/oracle/participants/{PARTY_ID_TYPE}/${PARTY_ID}?host={oracle-host}	|POST
payer-fund-transfer-DFSPID.bpmn	|party-lookup-request	|direct:send-party-lookup	|/parties/{PARTY_ID_TYPE}/{PARTY_ID}?host={als-host}	|GET
payer-fund-transfer-DFSPID.bpmn	|party-lookup	|rest:PUT:/switch/parties/MSISDN/{partyId}	|call zeebe message task	|-
payer-fund-transfer-DFSPID.bpmn	|quote	|direct:send-quote	|/quotes?host={quotes-host}	|POST
payer-fund-transfer-DFSPID.bpmn	|quote-callback	|rest:PUT:/switch/quotes/{QUOTE_ID}	|call zeebe message task	|-
payer-fund-transfer-DFSPID.bpmn	|quote-error	|rest:PUT:/switch/quotes/{QUOTE_ID}/error	|call zeebe message task	|-

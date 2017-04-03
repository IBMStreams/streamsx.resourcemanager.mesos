#!/bin/bash
curl -s XPOST http://localhost:8080/v2/apps -d@marathon.json.local -H "Content-Type: application/json"

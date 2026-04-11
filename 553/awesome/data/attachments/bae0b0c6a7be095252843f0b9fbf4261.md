## PATCH http://localhost:41717/echo → 200 OK

### Request Headers
```
PATCH http://localhost:41717/echo
```

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Sat, 11 Apr 2026 09:37:10 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:41717/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```

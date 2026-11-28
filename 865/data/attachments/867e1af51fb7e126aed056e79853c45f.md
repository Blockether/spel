## PATCH http://localhost:38493/echo → 200 OK

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
date: Sat, 28 Nov 2026 11:57:15 GMT
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
curl 'http://localhost:38493/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```

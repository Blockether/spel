## PATCH http://localhost:34967/echo → 200 OK

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
date: Wed, 11 Nov 2026 22:40:06 GMT
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
curl 'http://localhost:34967/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```

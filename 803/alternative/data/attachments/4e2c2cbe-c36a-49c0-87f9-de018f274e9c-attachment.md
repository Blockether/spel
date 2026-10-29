## PATCH http://localhost:39027/echo → 200 OK

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
date: Thu, 29 Oct 2026 12:48:55 GMT
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
curl 'http://localhost:39027/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```

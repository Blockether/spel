## PATCH http://localhost:35413/echo → 200 OK

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
date: Sat, 21 Nov 2026 19:57:25 GMT
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
curl 'http://localhost:35413/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```

## PATCH http://localhost:43923/echo → 200 OK

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
date: Wed, 08 Jul 2026 11:03:54 GMT
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
curl 'http://localhost:43923/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```

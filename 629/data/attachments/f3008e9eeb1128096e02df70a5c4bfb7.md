## PATCH http://localhost:38879/echo → 200 OK

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
date: Wed, 08 Jul 2026 16:44:31 GMT
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
curl 'http://localhost:38879/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```

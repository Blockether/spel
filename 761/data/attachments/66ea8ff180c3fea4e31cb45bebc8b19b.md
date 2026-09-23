## PATCH http://localhost:42613/echo → 200 OK

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
date: Wed, 23 Sep 2026 12:25:49 GMT
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
curl 'http://localhost:42613/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```

## PATCH http://localhost:46627/echo → 200 OK

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
date: Mon, 27 Jul 2026 10:08:41 GMT
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
curl 'http://localhost:46627/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```

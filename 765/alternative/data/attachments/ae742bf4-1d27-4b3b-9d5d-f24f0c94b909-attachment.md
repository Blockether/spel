## PUT http://localhost:37217/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Sun, 27 Sep 2026 12:53:42 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:37217/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```

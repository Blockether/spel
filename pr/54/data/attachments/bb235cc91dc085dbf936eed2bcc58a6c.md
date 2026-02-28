## PUT http://localhost:41139/echo → 200 OK

### Request Headers
```
PUT http://localhost:41139/echo
```

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
date: Sat, 28 Feb 2026 12:31:45 GMT
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
curl 'http://localhost:41139/echo' \
  -X PUT \
  -d '{"name":"Eve Updated"}'
```

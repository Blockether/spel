## DELETE http://localhost:36681/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 03 Aug 2026 02:53:17 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:36681/echo' \
  -X DELETE
```

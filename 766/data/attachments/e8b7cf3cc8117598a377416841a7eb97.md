## DELETE http://localhost:43239/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 27 Sep 2026 16:41:06 GMT
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
curl 'http://localhost:43239/echo' \
  -X DELETE
```

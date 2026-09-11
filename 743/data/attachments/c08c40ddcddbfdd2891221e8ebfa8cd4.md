## DELETE http://localhost:35531/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 11 Sep 2026 08:05:07 GMT
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
curl 'http://localhost:35531/echo' \
  -X DELETE
```
